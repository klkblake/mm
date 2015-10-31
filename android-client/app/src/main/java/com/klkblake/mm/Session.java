package com.klkblake.mm;

import android.util.LongSparseArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.klkblake.mm.Message.TYPE_PHOTOS;
import static com.klkblake.mm.Message.TYPE_TEXT;
import static com.klkblake.mm.Util.min;
import static com.klkblake.mm.Util.utf8Decode;
import static com.klkblake.mm.Util.utf8Encode;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

/**
 * Created by kyle on 15/10/15.
 */
public class Session implements Runnable {
    public static final int CONTROL_LENGTH_SIZE = 3;
    public static final int CONTROL_HEADER_SIZE = CONTROL_LENGTH_SIZE + 1;
    public static final int DATA_LENGTH_SIZE = 2;
    public static final int DATA_HEADER_SIZE = DATA_LENGTH_SIZE + 8;
    public static final int CONTROL_FOOTER_SIZE = 1;
    // TODO Always have all past messages loaded
    public static final int TYPE_SHIFT = 29;
    public static final int INDEX_MASK = (1 << TYPE_SHIFT) - 1;
    public static final int CHUNK_SIZE = 65536;

    private volatile boolean dead = true;
    private Selector selector;
    private SocketChannel controlChannel;
    private SocketChannel dataChannel;
    private SelectionKey controlKey;
    private SelectionKey dataKey;
    private ByteBuffer controlSendBuf = ByteBuffer.allocate(CONTROL_HEADER_SIZE + 999 + CONTROL_FOOTER_SIZE);
    private ByteBuffer controlRecvBuf = ByteBuffer.allocate(CONTROL_HEADER_SIZE + 999 + CONTROL_FOOTER_SIZE);
    private ByteBuffer dataSendBuf = ByteBuffer.allocate(DATA_HEADER_SIZE + CHUNK_SIZE);
    private ByteBuffer dataRecvBuf = ByteBuffer.allocate(DATA_HEADER_SIZE + CHUNK_SIZE);
    // TODO make sure to track pending data transfer set so we can resume after net drop

    private final ConcurrentLinkedQueue<SendingMessage> messagesToSend = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<SendingMessage> messagesPending = new ArrayDeque<>();
    private final PriorityQueue<SendingData> dataToSend = new PriorityQueue<>();
    private final LongSparseArray<SendingData> dataPending = new LongSparseArray<>();
    private final SessionListener listener;

    // TODO implement a disk cache
    // TODO always have metadata for *all* previous messages stored
    private final String photosDir;
    // FIXME signed/unsigned bugs. probably store compressed here, and expand in use?
    private LongArray timestamps = new LongArray();
    private BooleanArray authors = new BooleanArray();
    private IntArray indexes = new IntArray();
    // Type 0 - Pending content
    // TODO implement pending data
    // Type 1 - Text
    private ArrayList<byte[]> texts = new ArrayList<>(); // TODO manual string alloc?
    // Type 2 - Photos
    private ShortArray photoCounts = new ShortArray();

    public Session(String photosDir, SessionListener listener) {
        this.photosDir = photosDir;
        this.listener = listener;
        controlSendBuf.order(ByteOrder.LITTLE_ENDIAN);
        controlRecvBuf.order(ByteOrder.LITTLE_ENDIAN);
        dataSendBuf.order(ByteOrder.LITTLE_ENDIAN);
        dataRecvBuf.order(ByteOrder.LITTLE_ENDIAN);

        try {
            restart();
        } catch (IOException ignored) {
        }
    }

    public boolean isDead() {
        return dead;
    }

    public void restart() throws IOException {
        if (!dead) {
            throw new IllegalStateException("Restarting already running session!");
        }

        if (selector == null) {
            selector = Selector.open();
        }
        controlSendBuf.rewind();
        controlRecvBuf.rewind();
        dataSendBuf.rewind();
        dataRecvBuf.rewind();
        controlSendBuf.limit(0);
        controlRecvBuf.limit(CONTROL_LENGTH_SIZE);
        dataSendBuf.limit(0);
        dataRecvBuf.limit(DATA_LENGTH_SIZE);

        dead = false;
        new Thread(this, "Session Thread").start();
    }

    private void storeMessage(int messageID, long timestamp, boolean author, int type, int index) {
        timestamps.set(messageID, timestamp);
        authors.set(messageID, author);
        indexes.set(messageID, type << Session.TYPE_SHIFT | index & Session.INDEX_MASK);
    }

    private void storeMessage(int messageID, long timestamp, boolean author, String message) {
        texts.add(utf8Encode(message));
        storeMessage(messageID, timestamp, author, TYPE_TEXT, texts.size() - 1);
    }

    private void storePhotos(int messageID, long timestamp, boolean author, short photoCount) {
        photoCounts.add(photoCount);
        storeMessage(messageID, timestamp, author, TYPE_PHOTOS, photoCounts.count - 1);
    }

    private File fileForPart(int messageID, int partID, boolean isSingle) throws FilesystemFailure {
        if (isSingle) {
            return new File(photosDir, messageID + ".jpg");
        } else {
            File dir = new File(photosDir, Integer.toString(messageID));
            if (!dir.mkdir() && !dir.isDirectory()) {
                throw new FilesystemFailure("Could not create directory " + dir.getAbsolutePath());
            }
            return new File(dir, partID + ".jpg");
        }
    }

    private void expect(char expected) throws ProtocolFailure {
        char c = (char) controlSendBuf.get(); // TODO unsigned widen
        if (c != expected) {
            throw new ProtocolFailure("Expected '" + expected + "', got '" + c + "'");
        }
    }

    private long decodeHex(ByteBuffer buf, int length) throws ProtocolFailure {
        long value = 0;
        for (int i = 0; i < length; i++) {

            char c = (char) buf.get(); // TODO unsigned widen
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                throw new ProtocolFailure("Expected hex digit, got '" + c + "'");
            }
            value <<= 4;
            if (c < 'a') {
                value |= c - '0';
            } else {
                value |= c - 'a' + 10;
            }
        }
        return value;
    }

    private void putLength(int length) {
        controlSendBuf.put((byte) (length / 100 + '0'));
        controlSendBuf.put((byte) (length % 100 / 10 + '0'));
        controlSendBuf.put((byte) (length % 10 + '0'));
        controlSendBuf.put((byte) ' ');
    }

    private void putHexSize(int size) {
        for (int shift = 28; shift >= 0; shift -= 4) {
            int value = (size >> shift) & 0xf;
            if (value < 10) {
                controlSendBuf.put((byte) (value + '0'));
            } else {
                controlSendBuf.put((byte) (value - 10 + 'a'));
            }
        }
    }

    private static void read(SocketChannel channel, ByteBuffer buf) throws SocketFailure {
        try {
            channel.read(buf);
        } catch (IOException e) {
            throw new SocketFailure(e);
        }
    }

    private static void write(SocketChannel channel, ByteBuffer buf) throws SocketFailure {
        try {
            channel.write(buf);
        } catch (IOException e) {
            throw new SocketFailure(e);
        }
    }

    @Override
    public void run() {
        Failure failure = null;
        try {
            try {
                controlChannel = SocketChannel.open(new InetSocketAddress("klkblake.com", 29192));
                dataChannel = SocketChannel.open(new InetSocketAddress("klkblake.com", 29292));
                controlKey = controlChannel.register(selector, OP_READ);
                dataKey = dataChannel.register(selector, OP_READ);
            } catch (IOException e) {
                throw new SocketFailure(e);
            }
            while (!dead) {
                try {
                    selector.select();
                } catch (IOException e) {
                    throw new SocketFailure(e);
                }
                if (controlKey.isWritable() && controlSendBuf.hasRemaining()) {
                    write(controlChannel, controlSendBuf);
                }
                if (dataKey.isWritable() && dataSendBuf.hasRemaining()) {
                    write(dataChannel, dataSendBuf);
                }
                if (controlKey.isReadable()) {
                    read(controlChannel, controlRecvBuf);
                    if (!controlRecvBuf.hasRemaining() && controlRecvBuf.limit() == CONTROL_LENGTH_SIZE) {
                        int length = 0;
                        controlRecvBuf.rewind();
                        for (int i = 0; i < CONTROL_LENGTH_SIZE; i++) {
                            char c = (char) controlRecvBuf.get(); // TODO unsigned widen
                            if (c < '0' || c > '9') {
                                throw new ProtocolFailure("Expected digit, got '" + c + "'");
                            }
                            length *= 10;
                            length += c - '0';
                        }
                        if (length == 0) {
                            throw new ProtocolFailure("Zero length message");
                        }
                        controlRecvBuf.limit(CONTROL_HEADER_SIZE + length + CONTROL_FOOTER_SIZE);
                        read(controlChannel, controlRecvBuf);
                    }
                    //TODO handle the message
                    if (!controlRecvBuf.hasRemaining()) {
                        if (controlRecvBuf.limit() == 4 + 4 + 8 + 1 + 16 + 1) {
                            controlRecvBuf.position(3);
                            expect(' ');
                            expect('A');
                            expect('C');
                            expect('K');
                            expect(' ');
                            int id = (int) decodeHex(controlRecvBuf, 8);
                            expect(' ');
                            long timestamp = decodeHex(controlRecvBuf, 16);
                            expect('\n');
                            SendingMessage message = messagesPending.poll();
                            if (message == null) {
                                throw new ProtocolFailure("Received ACK with no messages pending");
                            }
                            if (message.type == TYPE_TEXT) {
                                storeMessage(id, timestamp, Message.AUTHOR_US, message.message);
                                listener.receivedMessage(id, timestamp, Message.AUTHOR_US, message.message);
                            } else if (message.type == Message.TYPE_PHOTOS) {
                                for (int i = 0; i < message.photos.length; i++) {
                                    dataToSend.add(new SendingData(id, i, message.photos[i],
                                            message.photoSizes[i], message.photos.length == 1));
                                }
                                storePhotos(id, timestamp, Message.AUTHOR_US, (short) message.photos.length);
                                listener.receivedMessage(id, timestamp, Message.AUTHOR_US, message.photos.length);
                            }
                        } else if (controlRecvBuf.limit() == 4 + 5 + 8 + 1 + 4 + 1) {
                            controlRecvBuf.position(3);
                            expect(' ');
                            expect('D');
                            expect('A');
                            expect('C');
                            expect('K');
                            expect(' ');
                            int id = (int) decodeHex(controlRecvBuf, 8);
                            expect(' ');
                            int part = (int) decodeHex(controlRecvBuf, 2);
                            expect('\n');
                            SendingData data = dataPending.get((long) id << 32 | part);
                            if (data == null) {
                                throw new ProtocolFailure("Received DACK for non-pending data");
                            }
                            if (!data.photo.renameTo(fileForPart(data.messageID, data.partID, data.isSingle))) {
                                throw new FilesystemFailure("Couldn't move part to appropriate file");
                            }
                            listener.receivedPart(data.messageID, data.partID);
                        }
                    }
                }
                if (dataKey.isReadable()) {
                    read(dataChannel, dataRecvBuf);
                    if (!dataRecvBuf.hasRemaining() && dataRecvBuf.limit() == DATA_LENGTH_SIZE) {
                        int length = 0;
                        dataRecvBuf.rewind();
                        for (int i = 0; i < DATA_LENGTH_SIZE; i++) {
                            int c = dataRecvBuf.get();
                            length |= c << (i * 8);
                        }
                        length++;
                        dataRecvBuf.limit(DATA_HEADER_SIZE + length);
                        read(dataChannel, dataRecvBuf);
                    }
                    // TODO handle the message
                }
                if (controlKey.isWritable()) {
                    while (!messagesToSend.isEmpty() && !controlSendBuf.hasRemaining()) {
                        SendingMessage message = messagesToSend.remove();
                        messagesPending.add(message);
                        if (message.type == TYPE_TEXT) {
                            // TODO use CharsetEncoder?
                            byte[] encoded = utf8Encode(message.message);
                            // TODO enforce limits
                            for (int i = 0; i < encoded.length; i++) {
                                if (encoded[i] == '\n') {
                                    encoded[i] = 127;
                                }
                                if (encoded[i] < ' ' && encoded[i] != '\t') {
                                    throw new AssertionFailure("Illegal character in message");
                                }
                            }
                            // TODO: BIGTEXT
                            if (encoded.length > 713) {
                                throw new AssertionFailure("Message too long");
                            }
                            int totalLength = encoded.length + 5;
                            putLength(totalLength);
                            controlSendBuf.put(new byte[]{'T', 'E', 'X', 'T', ' '});
                            controlSendBuf.put(encoded);
                            controlSendBuf.put((byte) '\n');
                            write(controlChannel, controlSendBuf);
                        } else if (message.type == Message.TYPE_PHOTOS) {
                            if (message.photos.length > 79) {
                                throw new AssertionFailure("Too many photos");
                            }
                            int totalLength = 6 + 9 * message.photos.length;
                            putLength(totalLength);
                            controlSendBuf.put(new byte[]{'P', 'H', 'O', 'T', 'O'});
                            for (int i = 0; i < message.photos.length; i++) {
                                controlSendBuf.put((byte) ' ');
                                // XXX do we want to treat the file not existing here as a recoverable error?
                                // XXX we definitely don't want to deliver a file that is >2GB!
                                message.photoSizes[i] = message.photos[i].length();
                                if (message.photoSizes[i] == 0) {
                                    throw new FilesystemFailure("Part file does not exist");
                                }
                                putHexSize((int) message.photoSizes[i]);
                            }
                            controlSendBuf.put((byte) '\n');
                            write(controlChannel, controlSendBuf);
                        }
                    }
                }
                if (dataKey.isWritable()) {
                    while (!dataToSend.isEmpty() && !dataSendBuf.hasRemaining()) {
                        SendingData data = dataToSend.remove();
                        if (data.channel == null) {
                            try {
                                data.channel = new FileInputStream(data.photo).getChannel();
                            } catch (FileNotFoundException e) {
                                throw new FilesystemFailure(e);
                            }
                        }
                        long remaining = data.photoSize - data.chunksSent * CHUNK_SIZE;
                        long sizeToSend = min(remaining, CHUNK_SIZE);
                        // TODO seriously consider our limits/sizes.
                        dataSendBuf.putShort((short) (sizeToSend - 1));
                        dataSendBuf.putInt(data.messageID);
                        dataSendBuf.putShort((short) data.partID);
                        dataSendBuf.putShort((short) data.chunksSent++);
                        try {
                            data.channel.read(dataSendBuf);
                        } catch (IOException e) {
                            throw new FilesystemFailure(e);
                        }
                        write(dataChannel, dataSendBuf);
                        if (data.chunksSent * CHUNK_SIZE >= data.photoSize) {
                            // XXX this breaks if we bump up the max messageID
                            dataPending.put((long) data.messageID << 32 | data.partID, data);
                            try {
                                data.channel.close();
                            } catch (IOException ignored) {
                            }
                        } else {
                            dataToSend.add(data);
                        }
                    }
                }
                if (!controlSendBuf.hasRemaining() && messagesToSend.isEmpty() && (controlKey.interestOps() & OP_WRITE) != 0) {
                    controlKey.interestOps(OP_READ);
                } else if ((controlKey.interestOps() & OP_WRITE) == 0) {
                    controlKey.interestOps(OP_READ | OP_WRITE);
                }
                if (!dataSendBuf.hasRemaining() && dataToSend.isEmpty() && (dataKey.interestOps() & OP_WRITE) != 0) {
                    dataKey.interestOps(OP_READ);
                } else if ((dataKey.interestOps() & OP_WRITE) == 0) {
                    dataKey.interestOps(OP_READ | OP_WRITE);
                }
            }
        } catch (Failure f) {
            failure = f;
        }
        if (controlChannel != null) {
            try {
                controlChannel.close();
            } catch (IOException ignored) {
            }
        }
        if (dataChannel != null) {
            try {
                dataChannel.close();
            } catch (IOException ignored) {
            }
        }
        if (controlKey != null) {
            controlKey.cancel();
        }
        if (dataKey != null) {
            dataKey.cancel();
        }
        messagesPending.clear();
        dataToSend.clear();
        dataPending.clear();
        dead = true;
        if (failure != null) {
            failure.notifyListener(listener);
        }
    }

    private void enqueueMessage(SendingMessage message) {
        messagesToSend.add(message);
        if (selector != null) {
            selector.wakeup();
        }
    }

    public void sendMessage(String message) {
        enqueueMessage(new SendingMessage(message));
    }

    public void sendPhotos(File... photos) {
        enqueueMessage(new SendingMessage(photos));
    }

    public Message getMessage(int id) {
        long timestamp = timestamps.data[id];
        boolean author = authors.data[id];
        int tyindex = indexes.data[id];
        int type = tyindex >> TYPE_SHIFT;
        int index = tyindex & INDEX_MASK;
        if (type == TYPE_TEXT) {
            return new Message(id, timestamp, author, utf8Decode(texts.get(index)));
        } else if (type == TYPE_PHOTOS) {
            return new Message(id, timestamp, author, photoCounts.data[index]);
        }
        throw new AssertionError("Unhandled type in getMessage");
    }

    public int getMessageCount() {
        return timestamps.count;
    }

    public void close() {
        dead = true;
        if (selector != null) {
            selector.wakeup();
        }
    }

    private static abstract class Failure extends Exception {
        public Failure(String message) {
            super(message);
        }

        public Failure(Throwable cause) {
            super(cause);
        }

        public abstract void notifyListener(SessionListener listener);
    }

    private static class SocketFailure extends Failure {
        public SocketFailure(Throwable cause) {
            super(cause);
        }

        @Override
        public void notifyListener(SessionListener listener) {
            listener.networkFailed(getCause());
        }
    }

    private static class ProtocolFailure extends Failure {
        public ProtocolFailure(String message) {
            super(message);
        }

        @Override
        public void notifyListener(SessionListener listener) {
            listener.protocolViolation(getMessage());
        }
    }

    private static class FilesystemFailure extends Failure {
        public FilesystemFailure(String message) {
            super(message);
        }

        public FilesystemFailure(Throwable cause) {
            super(cause);
        }

        @Override
        public void notifyListener(SessionListener listener) {
            listener.filesystemFailed(this);
        }
    }

    private static class AssertionFailure extends Failure {
        public AssertionFailure(String message) {
            super(message);
        }

        @Override
        public void notifyListener(SessionListener listener) {
            listener.assertionFired(this);
        }
    }
}