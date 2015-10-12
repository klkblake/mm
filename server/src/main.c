#include "common.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/epoll.h>
#include <pthread.h>

// TODO do we want to do the whole "embed the key into the ENCODE option" thing?

#define LISTEN_QUEUE_SIZE 128
#define CONNECTIONS_PER_WAIT 16

// The max number of open fds we use for things other than client connections
// std{in,out,err} + epoll fds + listening sockets
#define NUM_OTHER_FILES (3 + 2 + 2)
// TODO figure out how many connections swamps us, drop this value to match
// num users * devices per user * open conversations per user
#define MAX_SESSIONS (5 * 3 * 4)
#define MAX_CONNECTIONS (MAX_SESSIONS * 2)
#define NUM_POOLED_DATA_BUFFERS 8

#define CONTROL_LENGTH_SIZE 3
#define CONTROL_HEADER_SIZE (CONTROL_LENGTH_SIZE + 1)
#define CONTROL_BUF_SIZE (CONTROL_HEADER_SIZE + 999 + 1)
#define DATA_SESSION_ID_SIZE 4
#define DATA_LENGTH_SIZE 2
#define DATA_HEADER_SIZE (DATA_LENGTH_SIZE + 8)
#define DATA_BUF_SIZE (DATA_HEADER_SIZE + 65536)


#define EPOLL_EVENTS (EPOLLIN | EPOLLOUT | EPOLLRDHUP | EPOLLET)
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
	int sockfd;
	b1 is_data;
	struct Session *session;
	union {
		ControlState cstate;
		DataState dstate;
	};
	b1 can_read;
	b1 can_write;
	b1 should_read;
	u32 bytes_expected;
	u32 bytes_to_send;
	u32 bytes_read;
	u32 bytes_written;
	u8 *sendbuf;
	u8 *recvbuf;
	union {
		u8 tinybuf[sizeof(u32)];
		u32 session_id;
	};
} Connection;

typedef struct Session {
	Connection *control_conn;
	Connection *data_conn;
	u8 csendbuf[CONTROL_BUF_SIZE];
	u8 crecvbuf[CONTROL_BUF_SIZE];
} Session;

// TODO ensure no false sharing
typedef struct {
	int epfd;

	u8 *databufs[NUM_POOLED_DATA_BUFFERS];

	Connection conns[MAX_CONNECTIONS];
	pthread_mutex_t conns_mutex;
	u32 conns_watermark;          // NEEDS MUTEX
	Connection *conns_first_free; // NEEDS MUTEX

	Session sessions[MAX_SESSIONS];
	pthread_mutex_t sessions_mutex;
	u32 sessions_watermark;       // NEEDS MUTEX
	Session *sessions_first_free; // NEEDS MUTEX
} ConnState;

global_variable ConnState conn_state = {
	.conns_mutex = PTHREAD_MUTEX_INITIALIZER,
	.sessions_mutex = PTHREAD_MUTEX_INITIALIZER,
};

internal
u8 *data_buf_alloc(void) {
	for (u32 i = 0; i < NUM_POOLED_DATA_BUFFERS; i++) {
		if (conn_state.databufs[i]) {
			u8 *buf = conn_state.databufs[i];
			conn_state.databufs[i] = NULL;
			return buf;
		}
	}
	return malloc(DATA_BUF_SIZE);
}

internal
void data_buf_free(u8 *buf) {
	for (u32 i = 0; i < NUM_POOLED_DATA_BUFFERS; i++) {
		if (!conn_state.databufs[i]) {
			memset(buf, 0, DATA_BUF_SIZE);
			conn_state.databufs[i] = buf;
			return;
		}
	}
	free(buf);
}

internal
void connection_free(Connection *conn) {
	if (conn->is_data) {
		data_buf_free(conn->sendbuf);
		data_buf_free(conn->recvbuf);
	}
	pthread_mutex_lock(&conn_state.conns_mutex);
	conn->sockfd = 0;
	conn->is_data = false;
	conn->cstate = 0;
	conn->bytes_read = 0;
	conn->bytes_written = 0;
	conn->sendbuf = NULL;
	conn->recvbuf = NULL;
	conn->session = (Session *) conn_state.conns_first_free;
	conn_state.conns_first_free = conn;
	pthread_mutex_unlock(&conn_state.conns_mutex);
}

internal
void session_free(Session *session) {
	pthread_mutex_lock(&conn_state.sessions_mutex);
	session->data_conn = NULL;
	memset(session->csendbuf, 0, CONTROL_BUF_SIZE);
	memset(session->crecvbuf, 0, CONTROL_BUF_SIZE);
	session->data_conn = (Connection *) conn_state.sessions_first_free;
	conn_state.sessions_first_free = session;
	pthread_mutex_unlock(&conn_state.sessions_mutex);
}

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

__attribute__((noreturn))
internal
void die(void)  {
	perror("mm");
	exit(1);
}

internal
int die_on_fail(int result) {
	if (result == -1) {
		die();
	}
	return result;
}

internal
void close_connection(Connection *conn) {
	if (epoll_ctl(conn_state.epfd, EPOLL_CTL_DEL, conn->sockfd, NULL)) {
		impossible("Error while unregistering socket from epoll");
	}
	close_or_log(conn->sockfd);
	connection_free(conn);
}

internal
void close_session(Session *session) {
	close_or_log(session->control_conn->sockfd);
	connection_free(session->control_conn);
	if (session->data_conn) {
		close_or_log(session->data_conn->sockfd);
		connection_free(session->data_conn);
	}
	session_free(session);
}

internal
void safe_close_session(struct epoll_event *events, int nevents, Connection *conn) {
	if (conn->session) {
		Session *session = conn->session;
		for (int i = 0; i < nevents; i++) {
			if (events[i].data.ptr == session->control_conn ||
			    events[i].data.ptr == session->data_conn) {
				events[i].events = 0;
			}
		}
		close_session(conn->session);
	} else {
		for (int i = 0; i < nevents; i++) {
			if (events[i].data.ptr == conn) {
				events[i].events = 0;
			}
		}
		close_connection(conn);
	}
}

internal
void *run_conn_processor(__attribute__((unused)) void *unused) {
	struct epoll_event events[CONNECTIONS_PER_WAIT];
	while (true) {
		int nevents = epoll_wait(conn_state.epfd, events, CONNECTIONS_PER_WAIT, -1);
		if (nevents == -1) {
			if (errno == EINTR) {
				continue;
			}
			impossible("epoll() failed");
		}
		for (int i = 0; i < nevents; i++) {
			struct epoll_event *remaining = events + i + 1;
			int nremaining = nevents - i - 1;
			Connection *conn = events[i].data.ptr;
			u32 event = events[i].events;
			if (event == 0) {
				continue;
			}
			if (event & EPOLLERR) {
				int error;
				socklen_t error_size = sizeof(error);
				if (getsockopt(conn->sockfd, SOL_SOCKET, SO_ERROR, &error, &error_size)) {
					impossible("Couldn't get socket error");
				}
				errno = error;
				perror("Received error on socket");
				safe_close_session(remaining, nremaining, conn);
				continue;
			}
			if (!conn->can_read) {
				conn->can_read = (event & EPOLLIN) || (event & EPOLLRDHUP) || (event & EPOLLHUP);
			}
			if (!conn->can_write) {
				conn->can_write = event & EPOLLOUT;
			}
			while (conn->can_read && conn->should_read) {
				if (conn->is_data && conn->recvbuf == NULL) {
					conn->recvbuf = data_buf_alloc();
				}
				u8 *buf = conn->recvbuf + conn->bytes_read;
				size_t count = conn->bytes_expected - conn->bytes_read;
				ssize_t nread = read(conn->sockfd, buf, count);
				if (nread == -1) {
					if (errno == EAGAIN || errno == EWOULDBLOCK) {
						conn->can_read = false;
						if (conn->is_data &&
						    conn->bytes_expected == 2 &&
						    conn->bytes_read == 0) {
							data_buf_free(conn->recvbuf);
							conn->recvbuf = NULL;
						}
						break;
					}
					perror("Socket read failed");
					safe_close_session(remaining, nremaining, conn);
					goto next_event;
				}
				if (nread == 0) {
					safe_close_session(remaining, nremaining, conn);
					goto next_event;
				}
				conn->bytes_read += nread;
				if (conn->bytes_read == conn->bytes_expected) {
					if (conn->is_data) {
						if (conn->recvbuf == conn->tinybuf) {
							if (conn->session_id >= conn_state.sessions_watermark ||
							    conn_state.sessions[conn->session_id].control_conn == NULL ||
							    conn_state.sessions[conn->session_id].data_conn != NULL) {
								printf("Data channel for non-existant session %d\n",
								       conn->session_id);
								safe_close_session(remaining, nremaining, conn);
								goto next_event;
							}
							conn->session = &conn_state.sessions[conn->session_id];
							conn->session->data_conn = conn;
							conn->recvbuf = NULL;
							conn->bytes_expected = DATA_LENGTH_SIZE;
							conn->bytes_read = 0;
							conn->sendbuf = conn->tinybuf;
							conn->sendbuf[0] = 0;
							conn->bytes_to_send = 1;
						} else if (conn->bytes_expected == DATA_LENGTH_SIZE) {
							u16 length =
								conn->recvbuf[0] | (u16) (conn->recvbuf[1] << 8);
							conn->bytes_expected = DATA_HEADER_SIZE + length;
						} else {
							// TODO do something with the message?
							printf("Got data message: ");
							fwrite(conn->recvbuf, 1, conn->bytes_read, stdout);
							printf("\n");
							conn->bytes_expected = DATA_LENGTH_SIZE;
							conn->bytes_read = 0;
						}
					} else {
						if (conn->bytes_expected == CONTROL_LENGTH_SIZE) {
							u16 length = 0;
							for (u32 j = 0; j < CONTROL_LENGTH_SIZE; j++) {
								u8 c = conn->recvbuf[j];
								if (c < '0' || c > '9') {
									// TODO report PROTOCOL VIOLATION
									printf("Non-digit in length\n");
									safe_close_session(remaining, nremaining,
									                   conn);
									goto next_event;
								}
								length *= 10;
								length += c - '0';
							}
							conn->bytes_expected = CONTROL_HEADER_SIZE + length + 1;
						} else {
							// TODO do something with the message?
							printf("Got control message: ");
							fwrite(conn->recvbuf, 1, conn->bytes_read, stdout);
							conn->bytes_expected = CONTROL_LENGTH_SIZE;
							conn->bytes_read = 0;
						}
					}
				}
			}
			while (conn->can_write && conn->bytes_to_send) {
				u8 *buf = conn->sendbuf + conn->bytes_written;
				size_t count = conn->bytes_to_send - conn->bytes_written;
				ssize_t nwritten = write(conn->sockfd, buf, count);
				if (nwritten == -1) {
					if (errno == EAGAIN || errno == EWOULDBLOCK) {
						conn->can_write = false;
						break;
					}
					perror("Socket write failed");
					safe_close_session(remaining, nremaining, conn);
					goto next_event;
				}
				if (nwritten == 0) {
					safe_close_session(remaining, nremaining, conn);
					goto next_event;
				}
				conn->bytes_written += nwritten;
				if (conn->bytes_written == conn->bytes_to_send) {
					if (conn->is_data) {
						if (conn->sendbuf != conn->tinybuf) {
								data_buf_free(conn->sendbuf);
						}
						conn->sendbuf = NULL;
					}
					conn->bytes_to_send = 0;
					conn->bytes_written = 0;
				}
			}
next_event:;
		}
	}
	return NULL;
}

internal
int setup_socket(u16 port) {
	int sockfd = socket(AF_INET, SOCK_STREAM, 0);
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

int main(void) {
	struct rlimit fdlimit;
	die_on_fail(getrlimit(RLIMIT_NOFILE, &fdlimit));
	rlim_t fdmax = fdlimit.rlim_max;
	fdlimit.rlim_cur = MAX_CONNECTIONS + NUM_OTHER_FILES;
	if (fdlimit.rlim_cur > fdlimit.rlim_max) {
		fdlimit.rlim_max = fdlimit.rlim_cur;
	}
	if (setrlimit(RLIMIT_NOFILE, &fdlimit)) {
		fdlimit.rlim_cur = fdmax;
		fdlimit.rlim_max = fdmax;
		if (setrlimit(RLIMIT_NOFILE, &fdlimit)) {
		    perror("Couldn't raise limit on number of open files");
		}
	}

	int csockfd = die_on_fail(setup_socket(29192));
	int dsockfd = die_on_fail(setup_socket(29292));

	int acceptfd = die_on_fail(epoll_create1(0));
	struct epoll_event cconnect_event = { EPOLLIN, { .fd = csockfd } };
	struct epoll_event dconnect_event = { EPOLLIN, { .fd = dsockfd } };
	die_on_fail(epoll_ctl(acceptfd, EPOLL_CTL_ADD, csockfd, &cconnect_event));
	die_on_fail(epoll_ctl(acceptfd, EPOLL_CTL_ADD, dsockfd, &dconnect_event));

	conn_state.epfd = die_on_fail(epoll_create1(0));
	
	pthread_t conn_processor;
	int error = pthread_create(&conn_processor, NULL, run_conn_processor, NULL);
	if (error) {
		errno = error;
		die(); // TODO figure out safe shutdown
	}

	struct epoll_event events[CONNECTIONS_PER_WAIT];
	while (true) {
		int nevents = epoll_wait(acceptfd, events, CONNECTIONS_PER_WAIT, -1);
		if (nevents == -1) {
			if (errno == EINTR) {
				continue;
			}
			impossible("epoll() failed");
		}
		for (int i = 0; i < nevents; i++) {
			if ((events[i].events & EPOLLIN) == 0) {
				continue;
			}
			int fd = events[i].data.fd;
			int clientfd = accept4(fd, NULL, NULL, SOCK_NONBLOCK);
			if (clientfd == -1) {
				if (errno == ENETDOWN ||
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

			pthread_mutex_lock(&conn_state.conns_mutex);
			if (conn_state.conns_watermark == MAX_CONNECTIONS &&
			    conn_state.conns_first_free == NULL) {
				close_or_log(clientfd);
				pthread_mutex_unlock(&conn_state.conns_mutex);
				continue;
			}
			Connection *conn;
			if (conn_state.conns_first_free != NULL) {
				conn = conn_state.conns_first_free;
				conn_state.conns_first_free = (Connection *) conn->session;
				conn->session = NULL;
			} else {
				conn = &conn_state.conns[conn_state.conns_watermark++];
			}
			pthread_mutex_unlock(&conn_state.conns_mutex);

			if (fd == csockfd) {
				pthread_mutex_lock(&conn_state.sessions_mutex);
				if (conn_state.sessions_watermark == MAX_SESSIONS &&
				    conn_state.sessions_first_free == NULL) {
					close_or_log(clientfd);
					pthread_mutex_unlock(&conn_state.sessions_mutex);
					connection_free(conn);
					continue;
				}
				Session *session;
				if (conn_state.sessions_first_free != NULL) {
					session = conn_state.sessions_first_free;
					conn_state.sessions_first_free = (Session *) session->data_conn;
					session->data_conn = NULL;
				} else {
					session = &conn_state.sessions[conn_state.sessions_watermark++];
				}
				pthread_mutex_unlock(&conn_state.sessions_mutex);
				conn->session = session;
				conn->sendbuf = session->csendbuf;
				conn->recvbuf = session->crecvbuf;
				conn->bytes_expected = CONTROL_LENGTH_SIZE;
			} else {
				conn->recvbuf = conn->tinybuf;
				conn->bytes_expected = DATA_SESSION_ID_SIZE;
			}

			conn->sockfd = clientfd;
			conn->is_data = fd == dsockfd;
			conn->should_read = true;

			// TODO test server against DOS/slowloris attacks
			struct epoll_event event = { EPOLL_EVENTS, { .ptr = conn } };
			die_on_fail(epoll_ctl(conn_state.epfd, EPOLL_CTL_ADD, clientfd, &event));
		}
	}
}
