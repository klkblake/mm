package com.klkblake.mm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * Created by kyle on 10/11/15.
 */
public final class Crypto {
    public static final int MACBYTES;
    private static final int PUBLICKEYBYTES;
    private static final int SECRETKEYBYTES;

    static {
        System.loadLibrary("mm-crypto");
        MACBYTES = getMACBYTES();
        PUBLICKEYBYTES = getPUBLICKEYBYTES();
        SECRETKEYBYTES = getSECRETKEYBYTES();
    }

    private static native int getMACBYTES();
    private static native int getPUBLICKEYBYTES();
    private static native int getSECRETKEYBYTES();

    @SuppressWarnings("unused")
    private final long ptr;

    private static native long create(byte[] pubkey, byte[] seckey);
    private static native void encrypt(long ptr, ByteBuffer buf, int position, int limit, long counter);
    private static native boolean decrypt(long ptr, ByteBuffer buf, int position, int limit, long counter);
    private static native void destroy(long ptr);

    public Crypto() {
        byte[] pubkey = getKey("key.pub", PUBLICKEYBYTES);
        byte[] seckey = getKey("key.sec", SECRETKEYBYTES);
        // XXX This is wrong! We want the *server's* public key here
        ptr = create(pubkey, seckey);
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

    private static byte[] getKey(String file, int size) {
        InputStream in = Crypto.class.getClassLoader().getResourceAsStream(file);
        if (in == null) {
            throw new RuntimeException("Can't locate key file");
        }
        byte[] key = new byte[size];
        int read = 0;
        while (true) {
            int res;
            try {
                res = in.read(key, read, key.length - read);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (res == -1) {
                if (read != key.length) {
                    throw new RuntimeException("Invalid key file!");
                } else {
                    break;
                }
            } else {
                read += res;
            }
        }
        return key;
    }
}
