#!/bin/bash
set -e

DEBUG=no

CC=${CC:-clang}

if [[ "$1" == debug ]]; then
	DEBUG=yes
	CFLAGS="-O0 -g $CFLAGS"
	if [[ "$2" == undefined ]]; then
		CFLAGS="-fsanitize=undefined $CFLAGS"
	elif [[ "$2" == address ]]; then
		CFLAGS="-fsanitize=address -fsanitize=leak $CFLAGS"
	elif [[ "$2" == memory ]]; then
		CFLAGS="-fsanitize=memory $CFLAGS"
	fi
fi

if [[ DEBUG == no ]]; then
	CFLAGS="-O3 $CFLAGS"
fi

CFLAGS="-I../common $SEARCH $CFLAGS"

CFLAGS="-Wall -Weverything -Wno-gnu-empty-initializer -Wno-zero-length-array -Wno-vla -Wno-padded -Wno-reserved-id-macro -Wno-missing-prototypes -Wno-unused-function -Wno-unused-macros -Wno-unused-variable -Wno-newline-eof $CFLAGS"

CFLAGS="-std=gnu11 -x c $CFLAGS"
