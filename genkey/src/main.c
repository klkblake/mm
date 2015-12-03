#include "common.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>

#include <sodium.h>


#define die() die_(__FILE__, __LINE__)
__attribute__((noreturn))
internal
void die_(char *file, u32 line)  {
	perror("genkey");
	fprintf(stderr, "Error occured at %s, %d\n", file, line);
	exit(1);
}

#define strappend(str1, str2) strappend_(str1, str2, sizeof(str2))
internal
char *strappend_(char *str1, char *str2, usize len2) {
	// len1 does not contain the final NUL, len2 does.
	usize len1 = strlen(str1);
	char *result = malloc(len1 + len2);
	memcpy(result, str1, len1);
	memcpy(result + len1, str2, len2);
	return result;
}

#define write_key(fname, key) write_key_(fname, key, sizeof(key))
internal
void write_key_(char *fname, u8 *key, usize len) {
	int fd = open(fname, O_WRONLY|O_CREAT|O_EXCL, 0600);
	if (fd == -1) {
		die();
	}
	while (len) {
		ssize_t count = write(fd, key, len);
		if (count == -1) {
			die();
		}
		key += count;
		len -= (usize) count;
	}
	if (close(fd)) {
		die();
	}
}

int main(int argc, char **argv) {
	if (argc != 2) {
		printf("Usage: genkey <key file prefix>\n");
		return 1;
	}
	char *key_prefix = argv[1];
	char *pubkey_file = strappend(key_prefix, ".pub");
	char *seckey_file = strappend(key_prefix, ".sec");
	u8 pubkey[crypto_box_PUBLICKEYBYTES];
	u8 seckey[crypto_box_SECRETKEYBYTES];
	crypto_box_keypair(pubkey, seckey);
	write_key(pubkey_file, pubkey);
	write_key(seckey_file, seckey);
}
