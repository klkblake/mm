#include <stdlib.h>
#include <jni.h>

#include <sodium.h>

typedef unsigned char u8;

struct crypto_state {
    u8 key[crypto_box_BEFORENMBYTES];
    struct {
            jlong nonce1;
            jlong nonce2;
    } nonce;
};

JNIEXPORT jint JNICALL
Java_com_klkblake_mm_common_Crypto_getMACBYTES(JNIEnv *env, jclass type) {
    return crypto_box_MACBYTES;
}

JNIEXPORT jint JNICALL
Java_com_klkblake_mm_common_Crypto_getKEYBYTES(JNIEnv *env, jclass type) {
    _Static_assert(crypto_box_PUBLICKEYBYTES == crypto_box_SECRETKEYBYTES, "Key sizes don't match");
    return crypto_box_PUBLICKEYBYTES;
}

JNIEXPORT jlong JNICALL
Java_com_klkblake_mm_common_Crypto_create(JNIEnv *env, jclass type, jbyteArray pubkey_,
                                   jbyteArray seckey_) {
    jbyte *pubkey = (*env)->GetByteArrayElements(env, pubkey_, NULL);
    jbyte *seckey = (*env)->GetByteArrayElements(env, seckey_, NULL);

    struct crypto_state *state = malloc(sizeof(struct crypto_state));
    crypto_box_beforenm(state->key, (u8 *) pubkey, (u8 *) seckey);
    randombytes_buf(&state->nonce.nonce1, sizeof(state->nonce.nonce1));
    memset(&state->nonce.nonce2, 0, sizeof(state->nonce.nonce2));

    (*env)->ReleaseByteArrayElements(env, pubkey_, pubkey, 0);
    (*env)->ReleaseByteArrayElements(env, seckey_, seckey, 0);
    return (jlong) state;
}

JNIEXPORT jlong JNICALL
Java_com_klkblake_mm_common_Crypto_getNonce1(JNIEnv *env, jclass type, jlong ptr) {
    struct crypto_state *state = (struct crypto_state *) ptr;
    return state->nonce.nonce1;
}

JNIEXPORT void JNICALL
Java_com_klkblake_mm_common_Crypto_setNonce2(JNIEnv *env, jclass type, jlong ptr, jlong nonce2) {
    struct crypto_state *state = (struct crypto_state *) ptr;
    state->nonce.nonce2 = nonce2;
}

static void
build_nonce(char nonce[static 24], struct crypto_state *state, jlong counter) {
    _Static_assert(crypto_box_NONCEBYTES == 24, "Wrong nonce size");
    _Static_assert(sizeof(state->nonce) == 16, "Wrong random nonce size");
    _Static_assert(sizeof(counter) == 8, "Wrong counter size");
    memcpy(nonce, &state->nonce, sizeof(state->nonce));
    memcpy(nonce + 16, &counter, sizeof(counter));
}

JNIEXPORT void JNICALL
Java_com_klkblake_mm_common_Crypto_encrypt(JNIEnv *env, jclass type, jlong ptr, jobject buf_, jint position,
                                    jint limit, jlong counter) {
    struct crypto_state *state = (struct crypto_state *) ptr;
    u8 *buf = (*env)->GetDirectBufferAddress(env, buf_);
    u8 nonce[24];
    build_nonce(nonce, state, counter);
    crypto_box_easy_afternm(buf, buf, (unsigned int) (limit - position), nonce, state->key);
}

JNIEXPORT jboolean JNICALL
Java_com_klkblake_mm_common_Crypto_decrypt(JNIEnv *env, jclass type, jlong ptr, jobject buf_, jint position,
                                    jint limit, jlong counter) {
    struct crypto_state *state = (struct crypto_state *) ptr;
    u8 *buf = (*env)->GetDirectBufferAddress(env, buf_);
    u8 nonce[24];
    build_nonce(nonce, state, counter);
    int result = crypto_box_open_easy_afternm(buf, buf, (unsigned int) (limit - position), nonce, state->key);
    return (jboolean) (result != -1);
}

JNIEXPORT void JNICALL
Java_com_klkblake_mm_common_Crypto_destroy(JNIEnv *env, jclass type, jlong ptr) {
    struct crypto_state *state = (struct crypto_state *) ptr;
    sodium_memzero(state, sizeof(*state));
    free(state);
}