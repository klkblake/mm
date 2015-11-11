package com.klkblake.mm;

import com.klkblake.mm.Failure.AssertionFailure;
import com.klkblake.mm.Failure.FilesystemFailure;
import com.klkblake.mm.Failure.ProtocolFailure;
import com.klkblake.mm.Failure.SocketFailure;

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
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.klkblake.mm.Message.TYPE_TEXT;
import static com.klkblake.mm.Util.min;
import static com.klkblake.mm.Util.ub2i;
import static com.klkblake.mm.Util.utf8Encode;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

/**
 * Created by kyle on 15/10/15.
 */
public class Session implements Runnable {
    public static final int LENGTH_SIZE = 2;
    public static final int DATA_HEADER_SIZE = 11;
    public static final int CONTROL_LENGTH_MAX = 1024;
    public static final int MAX_CHUNK_SIZE = 65536;
    private static final int MACBYTES = 16; // TODO get this from the crypto library
    private static final int MTYPE_ACK_SERVER = 0;
    private static final int MTYPE_DACK_SERVER = 1;
    public static final int MAX_PARTS = 251;
    public static final int MAX_SHORT_TEXT = 1006;
    private static final byte MTYPE_TEXT_MESSAGE_CLIENT = 2;
    private static final byte MTYPE_PART_MESSAGE_CLIENT = 3;
    private static final byte PTYPE_PHOTOS = 1;
    private static final int MIN_LENGTH = 1 + MACBYTES;
    private static final long MAX_FILE_SIZE = 4 * 1024 * 1024;

    private volatile boolean dead = true;
    private Selector selector;
    private SocketChannel controlChannel;
    private SocketChannel dataChannel;
    private SelectionKey controlKey;
    private SelectionKey dataKey;
    private ByteBuffer controlSendBuf = ByteBuffer.allocateDirect(LENGTH_SIZE + 1024 + MACBYTES);
    private ByteBuffer controlRecvBuf = ByteBuffer.allocateDirect(LENGTH_SIZE + 1024 + MACBYTES);
    private ByteBuffer dataSendBuf = ByteBuffer.allocateDirect(LENGTH_SIZE + DATA_HEADER_SIZE + MAX_CHUNK_SIZE + MACBYTES);
    private ByteBuffer dataRecvBuf = ByteBuffer.allocateDirect(LENGTH_SIZE + DATA_HEADER_SIZE + MAX_CHUNK_SIZE + MACBYTES);
    // TODO make sure to track pending data transfer set so we can resume after net drop

    private final ConcurrentLinkedQueue<SendingMessage> messagesToSend = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<SendingMessage> messagesPending = new ArrayDeque<>();
    private final PriorityQueue<SendingData> dataToSend = new PriorityQueue<>();
    private final ArrayDeque<SendingData> dataPending = new ArrayDeque<>();
    private final Storage storage;
    private final SessionListener listener;

    public Session(Storage storage, SessionListener listener) {
        this.storage = storage;
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
        controlSendBuf.limit(0);
        dataSendBuf.rewind();
        dataSendBuf.limit(0);
        controlRecvBuf.clear();
        dataRecvBuf.clear();

        dead = false;
        new Thread(this, "Session Thread").start();
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

    private static void ASSERT(boolean cond, String message) throws AssertionFailure {
        if (!cond) {
            throw new AssertionFailure(message);
        }
    }

    @Override
    public void run() {
        Failure failure = null;
        Crypto crypto = new Crypto();
        try {
            try {
                controlChannel = SocketChannel.open(new InetSocketAddress("klkblake.com", 29192));
                dataChannel = SocketChannel.open(new InetSocketAddress("klkblake.com", 29292));
                controlChannel.configureBlocking(false);
                dataChannel.configureBlocking(false);
                controlKey = controlChannel.register(selector, OP_READ);
                dataKey = dataChannel.register(selector, OP_READ);
            } catch (IOException e) {
                throw new SocketFailure(e);
            } // TODO implement initial exchange
            long nonce2 = 0;
            long controlClientCounter = 0;
            long dataClientCounter = 0;
            long controlServerCounter = 0;
            long dataServerCounter = 0;
            while (!dead) {
                try {
                    selector.select();
                } catch (IOException e) {
                    throw new SocketFailure(e);
                }
                if (controlKey.isReadable()) {
                    read(controlChannel, controlRecvBuf);
                    while (controlRecvBuf.position() >= LENGTH_SIZE) {
                        int length = Util.us2i(controlRecvBuf.getShort(0));
                        length++;
                        if (length > CONTROL_LENGTH_MAX) {
                            throw new ProtocolFailure("Message length " + length + " exceeded cap " + CONTROL_LENGTH_MAX);
                        }
                        length += MACBYTES;
                        if (controlRecvBuf.position() < LENGTH_SIZE + length) {
                            break;
                        }
                        int end = controlRecvBuf.position();
                        controlRecvBuf.position(LENGTH_SIZE);
                        controlRecvBuf.limit(LENGTH_SIZE + length);
                        crypto.decrypt(controlRecvBuf, controlServerCounter++ * 2 + 1);
                        //TODO handle the message
                        int type = ub2i(controlRecvBuf.get());
                        if (type == MTYPE_ACK_SERVER) {
                            long id = controlRecvBuf.getLong();
                            long timestamp = controlRecvBuf.getLong();
                            if (id < 0) {
                                throw new ProtocolFailure("Received ACK with negative message ID");
                            }
                            if (timestamp < 0) {
                                throw new ProtocolFailure("Received ACK with negative timestamp ID");
                            }
                            SendingMessage message = messagesPending.poll();
                            if (message == null) {
                                throw new ProtocolFailure("Received ACK with no messages pending");
                            }
                            if (message.type == TYPE_TEXT) {
                                storage.storeMessage(id, timestamp, Message.AUTHOR_US, message.message);
                                listener.receivedMessage(id, timestamp, Message.AUTHOR_US, message.message);
                            } else if (message.type == Message.TYPE_PHOTOS) {
                                for (int i = 0; i < message.photos.length; i++) {
                                    dataToSend.add(new SendingData(id, i, message.photos[i],
                                            message.photoSizes[i], message.photos.length == 1));
                                }
                                storage.storePhotos(id, timestamp, Message.AUTHOR_US, (short) message.photos.length);
                                listener.receivedMessage(id, timestamp, Message.AUTHOR_US, message.photos.length);
                            }
                        } else if (type == MTYPE_DACK_SERVER) {
                            long id = controlRecvBuf.getLong();
                            int part = ub2i(controlRecvBuf.get());
                            SendingData data = dataPending.poll();
                            if (data == null) {
                                throw new ProtocolFailure("Received DACK for non-pending data");
                            }
                            if (data.messageID != id || data.partID != part) {
                                throw new ProtocolFailure(String.format("Received out of order DACK. Got %d:%d, expected %d:%d",
                                        id, part, data.messageID, data.partID));
                            }
                            storage.storePhoto(data);
                            listener.receivedPart(data.messageID, data.partID);
                        } else {
                            throw new ProtocolFailure("Illegal server message type " + type);
                        }
                        controlRecvBuf.limit(end);
                        controlRecvBuf.position(LENGTH_SIZE + length);
                        controlRecvBuf.compact();
                    }
                }
                if (dataKey.isReadable()) {
                    read(dataChannel, dataRecvBuf);
                    while (controlRecvBuf.position() >= LENGTH_SIZE) {
                        int length = Util.us2i(dataRecvBuf.getShort(0));
                        length += MIN_LENGTH;
                        if (dataRecvBuf.position() < LENGTH_SIZE + length) {
                            break;
                        }
                        int end = dataRecvBuf.position();
                        dataRecvBuf.position(LENGTH_SIZE);
                        dataRecvBuf.limit(LENGTH_SIZE + length);
                        crypto.decrypt(dataRecvBuf, ~(dataServerCounter++ * 2 + 1));
                        //TODO handle the message
                        dataRecvBuf.limit(end);
                        dataRecvBuf.position(LENGTH_SIZE + length);
                        dataRecvBuf.compact();
                    }
                }
                if (controlKey.isWritable()) {
                    if (controlSendBuf.hasRemaining()) {
                        write(controlChannel, controlSendBuf);
                    }
                    while (!messagesToSend.isEmpty() && !controlSendBuf.hasRemaining()) {
                        controlSendBuf.position(LENGTH_SIZE);
                        controlSendBuf.limit(controlSendBuf.capacity());
                        boolean doMAC = true; // XXX false for initial messages because we don't do encryption for them
                        SendingMessage message = messagesToSend.remove();
                        messagesPending.add(message);
                        if (message.type == TYPE_TEXT) {
                            byte[] encoded = utf8Encode(message.message);
                            // TODO: BIGTEXT
                            ASSERT(encoded.length <= MAX_SHORT_TEXT, "Message too long");
                            controlSendBuf.put(MTYPE_TEXT_MESSAGE_CLIENT);
                            controlSendBuf.put(encoded);
                        } else if (message.type == Message.TYPE_PHOTOS) {
                            ASSERT(message.photos.length <= MAX_PARTS, "Too many photos");
                            controlSendBuf.put(MTYPE_PART_MESSAGE_CLIENT);
                            controlSendBuf.put(PTYPE_PHOTOS);
                            for (int i = 0; i < message.photos.length; i++) {
                                controlSendBuf.put((byte) ' ');
                                message.photoSizes[i] = message.photos[i].length();
                                if (message.photoSizes[i] == 0) {
                                    throw new FilesystemFailure("Photo file does not exist");
                                }
                                if (message.photoSizes[i] > MAX_FILE_SIZE) {
                                    throw new FilesystemFailure("Photo is too big");
                                }
                                controlSendBuf.putInt((int) message.photoSizes[i]);
                            }
                        } else {
                            throw new ProtocolFailure("Illegal client message type " + message.type);
                        }
                        if (doMAC) {
                            controlSendBuf.limit(controlSendBuf.position());
                            controlSendBuf.position(LENGTH_SIZE);
                            crypto.encrypt(controlSendBuf, controlClientCounter++ * 2);
                        } else {
                            controlSendBuf.flip();
                        }
                        int length = controlSendBuf.limit() - LENGTH_SIZE - MIN_LENGTH;
                        ASSERT(length >= 0 && length <= Short.MAX_VALUE, "message length outside bounds");
                        controlSendBuf.putShort(0, (short) length);
                        controlSendBuf.rewind();
                        write(controlChannel, controlSendBuf);
                    }
                }
                if (dataKey.isWritable()) {
                    if (dataSendBuf.hasRemaining()) {
                        write(dataChannel, dataSendBuf);
                    }
                    while (!dataToSend.isEmpty() && !dataSendBuf.hasRemaining()) {
                        SendingData data = dataToSend.remove();
                        if (data.channel == null) {
                            try {
                                data.channel = new FileInputStream(data.photo).getChannel();
                            } catch (FileNotFoundException e) {
                                throw new FilesystemFailure(e);
                            }
                        }
                        long remaining = data.photoSize - data.chunksSent * MAX_CHUNK_SIZE;
                        int sizeToSend = (int) min(remaining, MAX_CHUNK_SIZE);
                        dataSendBuf.clear();
                        int length = sizeToSend - MIN_LENGTH;
                        // We either (a) will do the MAC, or (b) are sending DataClientHello,
                        // which is known to exceed MIN_LENGTH.
                        ASSERT(length >= 0 && length <= Short.MAX_VALUE, "message length outside bounds");
                        dataSendBuf.putShort((short) length);
                        dataSendBuf.putLong(data.messageID);
                        dataSendBuf.put((byte) data.partID);
                        dataSendBuf.putShort((short) data.chunksSent++);
                        dataSendBuf.limit(dataSendBuf.position() + sizeToSend);
                        try {
                            data.channel.read(dataSendBuf);
                            ASSERT(!dataSendBuf.hasRemaining(), "read was non-blocking!");
                        } catch (IOException e) {
                            throw new FilesystemFailure(e);
                        }
                        // XXX only do for encrypted messages
                        dataSendBuf.position(LENGTH_SIZE);
                        crypto.encrypt(dataSendBuf, ~(dataClientCounter++ * 2));
                        dataSendBuf.rewind();
                        write(dataChannel, dataSendBuf);
                        if (data.chunksSent * MAX_CHUNK_SIZE >= data.photoSize) {
                            dataPending.add(data);
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
        crypto.close();
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

    public void close() {
        dead = true;
        if (selector != null) {
            selector.wakeup();
        }
    }
}
