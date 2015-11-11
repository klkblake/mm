#include <stdlib.h>
#include <stdio.h>
#include <jni.h>
#include <android/log.h>

#define PUBLICKEYBYTES 32
#define SECRETKEYBYTES 32

struct crypto_state {
    char pubkey[PUBLICKEYBYTES];
    char seckey[SECRETKEYBYTES];
    char nonce1[8];
};

JNIEXPORT jint JNICALL
Java_com_klkblake_mm_Crypto_getMACBYTES(JNIEnv *env, jclass type) {
    return 16;
}

JNIEXPORT jint JNICALL
Java_com_klkblake_mm_Crypto_getPUBLICKEYBYTES(JNIEnv *env, jclass type) {
    return PUBLICKEYBYTES;
}

JNIEXPORT jint JNICALL
Java_com_klkblake_mm_Crypto_getSECRETKEYBYTES(JNIEnv *env, jclass type) {
    return SECRETKEYBYTES;
}

JNIEXPORT jlong JNICALL
Java_com_klkblake_mm_Crypto_create(JNIEnv *env, jclass type, jbyteArray pubkey_,
                                   jbyteArray seckey_) {
    jbyte *pubkey = (*env)->GetByteArrayElements(env, pubkey_, NULL);
    jbyte *seckey = (*env)->GetByteArrayElements(env, seckey_, NULL);

    struct crypto_state *state = malloc(sizeof(struct crypto_state));
    memcpy(state->pubkey, pubkey, PUBLICKEYBYTES);
    memcpy(state->seckey, seckey, SECRETKEYBYTES);
    memset(state->nonce1, 0, sizeof(state->nonce1)); // XXX fill with random values

    (*env)->ReleaseByteArrayElements(env, pubkey_, pubkey, 0);
    (*env)->ReleaseByteArrayElements(env, seckey_, seckey, 0);
    return state;
}

JNIEXPORT void JNICALL
Java_com_klkblake_mm_Crypto_encrypt(JNIEnv *env, jclass type, jlong ptr, jobject buf_, jint position,
                                    jint limit, jlong counter) {
    struct crypto_state *state = ptr;
    char *buf = (*env)->GetDirectBufferAddress(env, buf_);
    // TODO do encryption
}

JNIEXPORT void JNICALL
Java_com_klkblake_mm_Crypto_decrypt(JNIEnv *env, jclass type, jlong ptr, jobject buf_, jint position,
                                    jint limit, jlong counter) {
    struct crypto_state *state = ptr;
    char *buf = (*env)->GetDirectBufferAddress(env, buf_);
    // TODO do decryption
}

JNIEXPORT void JNICALL
Java_com_klkblake_mm_Crypto_destroy(JNIEnv *env, jclass type, jlong ptr) {
    free(ptr);
}