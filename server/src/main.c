#include "common.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/epoll.h>

#include <sodium.h>

#define LISTEN_QUEUE_SIZE 128

// TODO figure out how many connections swamps us, drop this value to match
// num users * devices per user * open conversations per user
#define MAX_SESSIONS (5 * 4)
#define MAX_CONNECTIONS (MAX_SESSIONS * 2)
#define NUM_POOLED_DATA_BUFFERS 8

#define CONTROL_LENGTH_SIZE 3
#define CONTROL_HEADER_SIZE (CONTROL_LENGTH_SIZE + 1)
#define CONTROL_BUF_SIZE (CONTROL_HEADER_SIZE + 999 + 1)
#define DATA_SESSION_ID_SIZE 4
#define DATA_LENGTH_SIZE 2
#define DATA_HEADER_SIZE (DATA_LENGTH_SIZE + 8)
#define DATA_BUF_SIZE (DATA_HEADER_SIZE + 65536)

// TODO tune this parameter
#define EVENTS_PER_WAIT 16


// TODO offload photo sizes?
// XXX maybe some smaller ones for initial setup?

typedef enum {
	CSTATE_EXPECT_HELLO,
	CSTATE_SEND_HELLO,
	CSTATE_EXPECT_I_AM,
	CSTATE_WAITING_FOR_DATA,
	CSTATE_READY,

	CSTATE_SEND_BAD_VERSION,
	CSTATE_SEND_PROTOCOL_VIOLATION,
	CSTATE_SEND_UNKNOWN_CLIENT_USER,
	CSTATE_SEND_UNKNOWN_PEER_USER,

	CSTATE_END,
} ControlState;

typedef enum {
	DSTATE_EXPECT_SESSION_NUMBER,
	DSTATE_SEND_ACK,
	DSTATE_READY,
	DSTATE_READ,
	DSTATE_WRITE,
	DSTATE_READWRITE,
} DataState;

typedef struct {
	u32 position;
	u32 limit;
	u8 data[0];
} Buf;

typedef struct {
	Buf b;
	u8 data[CONTROL_BUF_SIZE];
} CBuf;

typedef struct {
	Buf b;
	u8 data[DATA_BUF_SIZE];
} DBuf;

#define buf_start(buf) buf_start_(&(buf)->b)
internal
u8 *buf_start_(Buf *buf) {
	return buf->data + buf->position;
}

#define buf_remaining(buf) buf_remaining_(&(buf)->b)
internal
u32 buf_remaining_(Buf *buf) {
	return buf->limit - buf->position;
}

#define buf_copy(dest, src) buf_copy_(&(dest)->b, &(src)->b)
internal
void buf_copy_(Buf *dest, Buf *src) {
	u32 len = src->limit - src->position;
	memcpy(dest->data + dest->position, src->data + src->position, len);
	src->position += len;
	dest->position += len;
}

#define buf_flip(buf) buf_flip_(&(buf)->b)
internal
void buf_flip_(Buf *buf) {
	buf->limit = buf->position;
	buf->position = 0;
}

#define buf_clear(buf) buf_clear_(&(buf)->b, array_count((buf)->data))
internal
void buf_clear_(Buf *buf, u32 capacity) {
	buf->position = 0;
	buf->limit = capacity;
}

#define buf_compact(buf) buf_compact_(&(buf)->b, array_count((buf)->data))
internal
void buf_compact_(Buf *buf, u32 capacity) {
	u32 len = buf->limit - buf->position;
	memmove(buf->data, buf->data + buf->position, len);
	buf->position = len;
	buf->limit = capacity;
}

typedef struct {
	struct Session *session;
	int sockfd;
	b32 waiting_to_write;
} Conn;

typedef struct {
	Conn c;
	CBuf sendbuf;
	CBuf recvbuf;
} CConn;

typedef struct {
	Conn c;
	DBuf sendbuf;
	DBuf recvbuf;
} DConn;

typedef struct Session {
	CConn *cconn;
	DConn *dconn;
} Session;

typedef struct {
	void *first_free;
	u32 watermark;
} Pool;

typedef struct {
	Pool header;
	CConn data[MAX_SESSIONS];
} CConnPool;

typedef struct {
	Pool header;
	DConn data[MAX_SESSIONS];
} DConnPool;

typedef struct {
	Pool header;
	Session data[MAX_SESSIONS];
} SessionPool;

#define pool_alloc(pool) pool_alloc_(&(pool)->header, (pool)->data, sizeof((pool)->data[0]), array_count((pool)->data))
internal
void *pool_alloc_(Pool *pool, void *data, u32 size, u32 capacity) {
	if (pool->first_free != NULL) {
		void *first = pool->first_free;
		pool->first_free = *((void **)first);
		return first;
	}
	if (pool->watermark < capacity) {
		return &((u8 *)data)[size * pool->watermark++];
	}
	return NULL;
}

#define pool_free(pool, ptr) pool_free_(&(pool)->header, ptr)
internal
void pool_free_(Pool *pool, void *ptr) {
	*((void **)ptr) = pool->first_free;
	pool->first_free = ptr;
}

typedef struct {
	int epfd;
	struct epoll_event events[16];
	u32 nevents;
	CConnPool cconn_pool;
	DConnPool dconn_pool;
	SessionPool session_pool;
} ConnState;

internal
void close_or_log(int fd) {
	if (close(fd)) {
		perror("Error while closing file descriptor");
	}
}

__attribute__((noreturn))
internal
void impossible(char *message)  {
	perror(message);
	exit(2);
}

internal
void close_session(ConnState *state, Session *session) {
	close_or_log(session->cconn->c.sockfd);
	pool_free(&state->cconn_pool, session->cconn);
	if (session->dconn) {
		close_or_log(session->dconn->c.sockfd);
		pool_free(&state->dconn_pool, session->dconn);
	}
	pool_free(&state->session_pool, session);
}

internal
void safe_close_cconn_session(ConnState *state, CConn *conn) {
	Session *session = conn->c.session;
	if (session->dconn) {
		for (u32 i = 0; i < state->nevents; i++) {
			if (state->events[i].data.ptr == session->dconn) {
				state->events[i].events = 0;
			}
		}
	}
	close_session(state, conn->c.session);
}

internal
void safe_close_dconn_session(ConnState *state, DConn *conn) {
	if (conn->c.session) {
		CConn *cconn = conn->c.session->cconn;
		u64 tagged = (u64)cconn | HIGH_BIT;
		for (u32 i = 0; i < state->nevents; i++) {
			if (state->events[i].data.ptr == cconn) {
				state->events[i].events = 0;
			}
		}
		close_session(state, conn->c.session);
	} else {
		close_or_log(conn->c.sockfd);
		pool_free(&state->dconn_pool, conn);
	}
}

#define die() die_(__FILE__, __LINE__)
__attribute__((noreturn))
internal
void die_(char *file, u32 line)  {
	fprintf(stderr, "Error at %s:%u", file, line);
	perror("");
	exit(1);
}

#define die_on_fail(result) die_on_fail_(result, __FILE__, __LINE__)
internal
int die_on_fail_(int result, char *file, u32 line) {
	if (result == -1) {
		die_(file, line);
	}
	return result;
}

internal
void conn_set_waiting_to_write(int epfd, Conn *conn, epoll_data_t tag, b32 waiting_to_write) {
	conn->waiting_to_write = waiting_to_write;
	struct epoll_event event = {
		EPOLLIN | EPOLLRDHUP | (waiting_to_write ? EPOLLOUT : 0),
		tag,
	};
	die_on_fail(epoll_ctl(epfd, EPOLL_CTL_MOD, conn->sockfd, &event));
}

internal
int setup_socket(u16 port) {
	int sockfd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
	if (sockfd == -1) {
		return -1;
	}
	int one = 1;
	if (setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one))) {
		return -1;
	}
	struct sockaddr_in addr = { AF_INET, htons(port), { INADDR_ANY }, { 0 } };
	if (bind(sockfd, (struct sockaddr *) &addr, sizeof(addr))) {
		return -1;
	}
	if (listen(sockfd, LISTEN_QUEUE_SIZE)) {
		return -1;
	}
	return sockfd;
}

//  1 -> success
//  0 -> peer cleanly disconnected
// -1 -> error
internal
s32 read_full(int sockfd, Buf *buf) {
	ssize nread = 0;
	do {
		buf->position += nread;
		u32 remaining = buf_remaining_(buf); // TODO C11 generic buf_remaining
		if (remaining == 0) {
			return 1;
		}
		do {
			nread = read(sockfd, buf_start_(buf), remaining);
		} while (nread == -1 && errno == EINTR);
	} while (nread > 0);
	if (nread == -1) {
		if (errno == EAGAIN || errno == EWOULDBLOCK) {
			return 1;
		} else {
			return -1;
		}
	} else {
		return 0;
	}
}

internal
b32 handle_control_read(ConnState *conn_state, CConn *conn) {
	s32 result = read_full(conn->c.sockfd, &conn->recvbuf.b);
	if (result != 1) {
		if (result == -1) {
			perror("Control socket read failed");
		}
		return false;
	}
	CBuf *sendbuf = &conn->sendbuf;
	CBuf *recvbuf = &conn->recvbuf;
	buf_flip(recvbuf);
	printf("Control read data: %.*s\n", recvbuf->b.limit, recvbuf->data);
	buf_compact(sendbuf);
	s32 overflow = (s32)buf_remaining(recvbuf) - (s32)buf_remaining(sendbuf);
	if (overflow > 0) {
		printf("Dropped %d bytes\n", overflow);
		recvbuf->b.limit -= (u32)overflow;
	}
	buf_copy(sendbuf, recvbuf);
	buf_flip(sendbuf);
	buf_compact(recvbuf);
	return true;
}

internal
b32 handle_data_read(ConnState *conn_state, DConn *conn) {
	s32 result = read_full(conn->c.sockfd, &conn->recvbuf.b);
	if (result != 1) {
		if (result == -1) {
			perror("Data socket read failed");
		}
		return false;
	}
	DBuf *sendbuf = &conn->sendbuf;
	DBuf *recvbuf = &conn->recvbuf;
	buf_flip(recvbuf);
	printf("Control read data: %.*s\n", recvbuf->b.limit, recvbuf->data);
	buf_compact(sendbuf);
	s32 overflow = (s32)buf_remaining(recvbuf) - (s32)buf_remaining(sendbuf);
	if (overflow > 0) {
		printf("Dropped %d bytes\n", overflow);
		recvbuf->b.limit -= (u32)overflow;
	}
	buf_copy(sendbuf, recvbuf);
	buf_flip(sendbuf);
	buf_compact(recvbuf);
	return true;
}

//  1 -> success
//  0 -> peer cleanly disconnected
// -1 -> error
internal
s32 write_full(int sockfd, Buf *buf) {
	ssize nwritten = 0;
	do {
		buf->position += nwritten;
		u32 remaining = buf_remaining_(buf); // TODO C11 generic buf_remaining
		if (remaining == 0) {
			return 1;
		}
		do {
			nwritten = write(sockfd, buf_start_(buf), remaining);
		} while (nwritten == -1 && errno == EINTR);
	} while (nwritten > 0);
	if (errno == EAGAIN || errno == EWOULDBLOCK) {
		return 1;
	} else if (errno == EPIPE) {
		return 0;
	} else {
		return -1;
	}
}

internal
b32 handle_control_write(ConnState *conn_state, CConn *conn) {
	s32 result = write_full(conn->c.sockfd, &conn->sendbuf.b);
	if (result != 1) {
		if (result == -1) {
			perror("Control socket write failed");
		}
		return false;
	}
	return true;
}

internal
b32 handle_data_write(ConnState *conn_state, DConn *conn) {
	s32 result = write_full(conn->c.sockfd, &conn->sendbuf.b);
	if (result != 1) {
		if (result == -1) {
			perror("Control socket write failed");
		}
		return false;
	}
	return true;
}

internal
void set_errno_from_socket(int sockfd) {
	int error;
	socklen_t error_size = sizeof(error);
	if (getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &error, &error_size)) {
		impossible("Couldn't get socket error");
	}
	errno = error;
}

global_variable ConnState conn_state;

int main(void) {
	int csockfd = die_on_fail(setup_socket(29192));
	int dsockfd = die_on_fail(setup_socket(29292));

	int epfd = die_on_fail(epoll_create1(0));
	conn_state.epfd = epfd;
	struct epoll_event cconnect_event = { EPOLLIN, { .u64 = 1 } };
	struct epoll_event dconnect_event = { EPOLLIN, { .u64 = 0 } };
	die_on_fail(epoll_ctl(epfd, EPOLL_CTL_ADD, csockfd, &cconnect_event));
	die_on_fail(epoll_ctl(epfd, EPOLL_CTL_ADD, dsockfd, &dconnect_event));

	while (true) {
		struct epoll_event *events = conn_state.events;
		s32 nevents = epoll_wait(epfd, events, array_count(conn_state.events), -1);
		if (nevents == -1) {
			if (errno == EINTR) {
				continue;
			}
			impossible("epoll() failed");
		}
		conn_state.nevents = (u32) nevents;
		for (u32 i = 0; i < conn_state.nevents; i++) {
			if (events[i].data.u64 <= 1) {
				b32 is_control = (b32) events[i].data.u64;
				if ((events[i].events & EPOLLERR) != 0) {
					set_errno_from_socket(is_control ? csockfd : dsockfd);
					perror("Received error on listening socket");
					continue;
				}
				if ((events[i].events & EPOLLHUP) != 0) {
					set_errno_from_socket(is_control ? csockfd : dsockfd);
					perror("Received HUP on listening socket");
					continue;
				}
				int acceptfd = is_control ? csockfd : dsockfd;
				int clientfd = accept4(acceptfd, NULL, NULL, SOCK_NONBLOCK);
				if (clientfd == -1) {
					if (errno == EAGAIN ||
					    errno == EWOULDBLOCK ||
					    errno == ENETDOWN ||
					    errno == EPROTO ||
					    errno == ENOPROTOOPT ||
					    errno == EHOSTDOWN ||
					    errno == ENONET ||
					    errno == EHOSTUNREACH ||
					    errno == EOPNOTSUPP ||
					    errno == ENETUNREACH ||
					    errno == ECONNABORTED ||
					    errno == EINTR) {
						continue;
					}
					die();
				}

				struct epoll_event event;
				if (is_control) {
					CConn *conn = pool_alloc(&conn_state.cconn_pool);
					if (!conn) {
						close_or_log(clientfd);
						continue;
					}
					*conn = (CConn){};
					conn->c.sockfd = clientfd;
					conn->recvbuf.b.limit = array_count(conn->sendbuf.data);
					conn->c.session = pool_alloc(&conn_state.session_pool);
					if (!conn->c.session) {
						pool_free(&conn_state.cconn_pool, conn);
						close_or_log(clientfd);
						continue;
					}
					*conn->c.session = (Session){};
					conn->c.session->cconn = conn;
					event.data.u64 = (u64)conn | HIGH_BIT;
				} else {
					DConn *conn = pool_alloc(&conn_state.dconn_pool);
					if (!conn) {
						close_or_log(clientfd);
						continue;
					}
					*conn = (DConn){};
					conn->c.sockfd = clientfd;
					conn->recvbuf.b.limit = array_count(conn->sendbuf.data);
					event.data.ptr = conn;
				}
				event.events = EPOLLIN | EPOLLRDHUP;
				die_on_fail(epoll_ctl(epfd, EPOLL_CTL_ADD, clientfd, &event));
			} else {
				u32 event = events[i].events;
				b32 can_read = (event & EPOLLIN) || (event & EPOLLRDHUP) || (event & EPOLLHUP);
				b32 can_write = event & EPOLLOUT;
				b32 is_control = (events[i].data.u64 & HIGH_BIT) != 0;
				if (is_control) {
					CConn *conn = (CConn *)(events[i].data.u64 &~ HIGH_BIT);
					if ((events[i].events & EPOLLERR) != 0) {
						set_errno_from_socket(conn->c.sockfd);
						perror("Received error on socket");
						safe_close_cconn_session(&conn_state, conn);
						continue;
					}
					if (can_read) {
						if (!handle_control_read(&conn_state, conn)) {
							safe_close_cconn_session(&conn_state, conn);
							continue;
						}
					}
					if (can_write) {
						if (!handle_control_write(&conn_state, conn)) {
							safe_close_cconn_session(&conn_state, conn);
							continue;
						}
					}
					if (buf_remaining(&conn->sendbuf) && !conn->c.waiting_to_write) {
						conn_set_waiting_to_write(epfd, &conn->c, events[i].data, true);
					} else if (conn->c.waiting_to_write) {
						conn_set_waiting_to_write(epfd, &conn->c, events[i].data, false);
					}
				} else {
					DConn *conn = events[i].data.ptr;
					if ((events[i].events & EPOLLERR) != 0) {
						set_errno_from_socket(conn->c.sockfd);
						perror("Received error on socket");
						safe_close_dconn_session(&conn_state, conn);
						continue;
					}
					if (can_read) {
						if (!handle_data_read(&conn_state, conn)) {
							safe_close_dconn_session(&conn_state, conn);
							continue;
						}
					}
					if (can_write) {
						if (!handle_data_write(&conn_state, conn)) {
							safe_close_dconn_session(&conn_state, conn);
							continue;
						}
					}
					if (buf_remaining(&conn->sendbuf) && !conn->c.waiting_to_write) {
						conn_set_waiting_to_write(epfd, &conn->c, events[i].data, true);
					} else if (conn->c.waiting_to_write) {
						conn_set_waiting_to_write(epfd, &conn->c, events[i].data, false);
					}
				}
			}
		}
	}
}
