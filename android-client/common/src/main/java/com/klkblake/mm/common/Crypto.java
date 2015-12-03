package com.klkblake.mm.common;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * Created by kyle on 10/11/15.
 */
public final class Crypto {
    public static final int MACBYTES;
    public static final int KEYBYTES;

    static {
        System.loadLibrary("mm-crypto");
        MACBYTES = getMACBYTES();
        KEYBYTES = getKEYBYTES();
    }

    private static native int getMACBYTES();
    private static native int getKEYBYTES();

    @SuppressWarnings("unused")
    private final long ptr;

    private static native long create(byte[] pubkey, byte[] seckey);
    private static native long getNonce1(long ptr);
    private static native void setNonce2(long ptr, long nonce2);
    private static native void encrypt(long ptr, ByteBuffer buf, int position, int limit, long counter);
    private static native boolean decrypt(long ptr, ByteBuffer buf, int position, int limit, long counter);
    private static native void destroy(long ptr);

    public Crypto(byte[] serverkey, byte[] seckey) {
        if (serverkey.length != KEYBYTES || seckey.length != KEYBYTES) {
            throw new IllegalArgumentException("Incorrect key size");
        }
        ptr = create(serverkey, seckey);
    }

    public long getNonce1() {
        return getNonce1(ptr);
    }

    public void setNonce2(long nonce2) {
        setNonce2(ptr, nonce2);
    }

    // Must be a direct buffer
    public void encrypt(ByteBuffer buf, long counter) {
        if (buf.capacity() - buf.limit() < MACBYTES) {
            throw new BufferOverflowException();
        }
        encrypt(ptr, buf, buf.position(), buf.limit(), counter);
        buf.limit(buf.limit() + MACBYTES);
    }

    public boolean decrypt(ByteBuffer buf, long counter) {
        boolean result = decrypt(ptr, buf, buf.position(), buf.limit(), counter);
        buf.limit(buf.limit() - MACBYTES);
        return result;
    }

    public void close() {
        destroy(ptr);
    }

}
