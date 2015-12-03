package com.klkblake.mm.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by kyle on 4/12/15.
 */
public class Resources {
    public static final byte[] SERVER_KEY;
    public static final byte[] PUBLIC_KEY;
    public static final byte[] SECRET_KEY;
    public static final List<byte[]> EMBEDDED_CONTACTS;

    static {
        SERVER_KEY = getKey("server.pub");
        PUBLIC_KEY = getKey("key.pub");
        SECRET_KEY = getKey("key.sec");
        EMBEDDED_CONTACTS = getContacts();
    }

    private static byte[] getKey(String file) {
        InputStream in = Resources.class.getClassLoader().getResourceAsStream(file);
        if (in == null) {
            throw new RuntimeException("Can't locate key");
        }
        byte[] key = new byte[Crypto.KEYBYTES];
        int read = 0;
        try {
            while (true) {
                int res = in.read(key, read, key.length - read);
                if (res == -1) {
                    if (read != key.length) {
                        throw new RuntimeException("Invalid key file!");
                    }
                    break;
                }
                read += res;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return key;
    }

    private static List<byte[]> getContacts() {
        InputStream inputStream = Resources.class.getClassLoader().getResourceAsStream("contacts.bin");
        if (inputStream == null) {
            throw new RuntimeException("Can't locate contacts file");
        }
        ReadableByteChannel in = Channels.newChannel(inputStream);
        ByteBuffer buf = ByteBuffer.allocate(4096);
        ArrayList<byte[]> contacts = new ArrayList<>();
        try {
            while (true) {
                int res = in.read(buf);
                buf.flip();
                while (buf.remaining() >= Crypto.KEYBYTES) {
                    byte[] key = new byte[Crypto.KEYBYTES];
                    buf.get(key);
                    contacts.add(key);
                }
                if (res == -1) {
                    if (buf.hasRemaining()) {
                        throw new RuntimeException("Invalid contacts file!");
                    }
                    break;
                }
                buf.compact();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        contacts.trimToSize();
        return Collections.unmodifiableList(contacts);
    }
}
