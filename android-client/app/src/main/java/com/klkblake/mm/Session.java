package com.klkblake.mm;

import com.klkblake.mm.Failure.AssertionFailure;
import com.klkblake.mm.Failure.AuthenticationFailure;
import com.klkblake.mm.Failure.FilesystemFailure;
import com.klkblake.mm.Failure.ProtocolFailure;
import com.klkblake.mm.Failure.SocketFailure;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.klkblake.mm.Crypto.MACBYTES;
import static com.klkblake.mm.Message.TYPE_TEXT;
import static com.klkblake.mm.Util.min;
import static com.klkblake.mm.Util.ub2i;
import static com.klkblake.mm.Util.us2i;
import static com.klkblake.mm.Util.utf8Decode;
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
    public static final int MAX_PARTS = 251;
    public static final int MAX_SHORT_TEXT = 1006;
    private static final int CONTROL_MIN_LENGTH = 1 + MACBYTES;
    public static final int DATA_MIN_LENGTH = DATA_HEADER_SIZE + CONTROL_MIN_LENGTH;
    private static final long MAX_FILE_SIZE = 4 * 1024 * 1024;

    public static final short PROTOCOL_VERSION = 0;

    private static final int STATE_SEND_HEL = 0;
    private static final int STATE_RECV_HEL = 1;
    private static final int STATE_SEND_DATA_HELLO = 2;
    private static final int STATE_SEND_LO = 3;
    private static final int STATE_RECV_LO = 4;
    private static final int STATE_READY = 5;

    private static final byte MTYPE_ACK_SERVER = 0;
    private static final byte MTYPE_DACK_SERVER = 1;
    private static final byte MTYPE_TEXT_MESSAGE_CLIENT = 2;
    private static final byte MTYPE_PART_MESSAGE_CLIENT = 3;

    private static final byte PTYPE_PHOTOS = 1;

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
    private int state;
    private long controlClientCounter;
    private long dataClientCounter;
    private long controlServerCounter;
    private long dataServerCounter;
    private Crypto crypto;

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
        // TODO consider moving to run()
        controlSendBuf.rewind();
        controlSendBuf.limit(0);
        dataSendBuf.rewind();
        dataSendBuf.limit(0);
        controlRecvBuf.clear();
        dataRecvBuf.clear();

        dead = false;
        new Thread(this, "Session Thread").start();
    }

    private static void ASSERT(boolean cond, String message) throws AssertionFailure {
        if (!cond) {
            throw new AssertionFailure(message);
        }
    }

    private static void read(SocketChannel channel, ByteBuffer buf) throws SocketFailure {
        try {
            channel.read(buf);
        } catch (IOException e) {
            throw new SocketFailure(e);
        }
    }

    private void writeAndUpdateState(SocketChannel channel, ByteBuffer buf) throws SocketFailure, AssertionFailure {
        ASSERT(buf.hasRemaining(), "tried to write empty buffer");
        try {
            channel.write(buf);
        } catch (IOException e) {
            throw new SocketFailure(e);
        }
        if (state != STATE_READY && !buf.hasRemaining()) {
            state++;
        }
    }

    private void newControlMessage() {
        controlSendBuf.position(LENGTH_SIZE);
        controlSendBuf.limit(controlSendBuf.capacity());
    }

    private void sendControlMessage(boolean doEncrypt) throws AssertionFailure, SocketFailure {
        controlSendBuf.limit(controlSendBuf.position());
        if (doEncrypt) {
            controlSendBuf.position(LENGTH_SIZE);
            crypto.encrypt(controlSendBuf, controlClientCounter++ * 2);
        }
        int length = controlSendBuf.limit() - LENGTH_SIZE - CONTROL_MIN_LENGTH;
        ASSERT(length >= 0 && length <= CONTROL_LENGTH_MAX - 1, "message length outside bounds");
        controlSendBuf.putShort(0, (short) length);
        controlSendBuf.rewind();
        writeAndUpdateState(controlChannel, controlSendBuf);
    }

    @Override
    public void run() {
        // TODO switch to using CurveCP as our transport
        Failure failure = null;
        byte[] pubkey = Crypto.getPublicKey();
        byte[] peerpubkey = new byte[32]; // TODO actually specify a peer.
        crypto = new Crypto();
        controlClientCounter = 0;
        dataClientCounter = 0;
        controlServerCounter = 0;
        dataServerCounter = 0;
        byte[] sessionToken = new byte[32];
        state = STATE_SEND_HEL;
        try {
            try {
                // TODO notify app on connection established
                controlChannel = SocketChannel.open(new InetSocketAddress("klkblake.com", 29192));
                dataChannel = SocketChannel.open(new InetSocketAddress("klkblake.com", 29292));
                controlChannel.configureBlocking(false);
                dataChannel.configureBlocking(false);
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
                if (controlKey.isReadable()) {
                    if (state == STATE_SEND_HEL || state == STATE_SEND_DATA_HELLO || state == STATE_SEND_LO) {
                        throw new ProtocolFailure("Received data in send state " + state);
                    }
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
                        if (state == STATE_RECV_HEL) {
                            int error = ub2i(controlRecvBuf.get());
                            if (error == 0) {
                                crypto.setNonce2(controlRecvBuf.getLong());
                            } else if (error == 1) {
                                final int minVersion = us2i(controlRecvBuf.getShort());
                                final int maxVersion = us2i(controlRecvBuf.getShort());
                                throw new Failure() {
                                    @Override
                                    public void notifyListener(SessionListener listener) {
                                        listener.badVersion(minVersion, maxVersion);
                                    }
                                };
                            } else if (error == 2) {
                                throw new Failure() {
                                    @Override
                                    public void notifyListener(SessionListener listener) {
                                        listener.unknownUser();
                                    }
                                };
                            } else {
                                throw new ProtocolFailure("Invalid error code " + error);
                            }
                        }
                        boolean verified = crypto.decrypt(controlRecvBuf, controlServerCounter++ * 2 + 1);
                        if (!verified) {
                            throw new AuthenticationFailure(true);
                        }
                        //TODO handle the message
                        if (state == STATE_RECV_HEL) {
                            controlRecvBuf.get(sessionToken);
                            int ourColor = controlRecvBuf.getInt();
                            byte[] ourAvatarSha256 = new byte[32];
                            controlRecvBuf.get(ourAvatarSha256);
                            byte[] ourName = new byte[controlRecvBuf.remaining()];
                            controlRecvBuf.get(ourName);
                            listener.receivedOurColor(ourColor);
                            listener.receivedOurName(utf8Decode(ourName));
                            listener.receivedOurAvatarSha256(ourAvatarSha256);
                            state++;
                        } else if (state == STATE_RECV_LO) {
                            int error = ub2i(controlRecvBuf.get());
                            if (error == 0) {
                                int peerColor = controlRecvBuf.getInt();
                                byte[] peerAvatarSha256 = new byte[32];
                                controlRecvBuf.get(peerAvatarSha256);
                                byte[] peerName = new byte[controlRecvBuf.remaining()];
                                controlRecvBuf.get(peerName);
                                listener.receivedPeerColor(peerColor);
                                listener.receivedPeerName(utf8Decode(peerName));
                                listener.receivedPeerAvatarSha256(peerAvatarSha256);
                            } else if (error == 1) {
                                throw new Failure() {
                                    @Override
                                    public void notifyListener(SessionListener listener) {
                                        listener.unknownPeer();
                                    }
                                };
                            } else {
                                throw new ProtocolFailure("Invalid error code " + error);
                            }
                            state++;
                        } else if (state == STATE_READY) {
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
                        } else {
                            throw new ProtocolFailure("Received message in unexpected state " + state);
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
                        length += CONTROL_MIN_LENGTH;
                        if (dataRecvBuf.position() < LENGTH_SIZE + length) {
                            break;
                        }
                        int end = dataRecvBuf.position();
                        dataRecvBuf.position(LENGTH_SIZE);
                        dataRecvBuf.limit(LENGTH_SIZE + length);
                        boolean verified = crypto.decrypt(dataRecvBuf, ~(dataServerCounter++ * 2 + 1));
                        if (!verified) {
                            throw new AuthenticationFailure(false);
                        }
                        //TODO handle the message
                        dataRecvBuf.limit(end);
                        dataRecvBuf.position(LENGTH_SIZE + length);
                        dataRecvBuf.compact();
                    }
                }
                if (controlKey.isWritable()) {
                    if (controlSendBuf.hasRemaining()) {
                        writeAndUpdateState(controlChannel, controlSendBuf);
                    }
                    if (!controlSendBuf.hasRemaining()) {
                        if (state == STATE_SEND_HEL) {
                            newControlMessage();
                            controlSendBuf.putShort(PROTOCOL_VERSION);
                            controlSendBuf.putLong(crypto.getNonce1());
                            controlSendBuf.put(pubkey);
                            sendControlMessage(false);
                        } else if (state == STATE_SEND_LO) {
                            newControlMessage();
                            controlSendBuf.put(peerpubkey);
                            sendControlMessage(true);
                        } else if (state == STATE_READY) {
                            while (!messagesToSend.isEmpty() && !controlSendBuf.hasRemaining()) {
                                newControlMessage();
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
                                sendControlMessage(true);
                            }
                        }
                    }
                }
                if (dataKey.isWritable()) {
                    if (dataSendBuf.hasRemaining()) {
                        writeAndUpdateState(dataChannel, dataSendBuf);
                    }
                    if (!dataSendBuf.hasRemaining()) {
                        if (state == STATE_SEND_DATA_HELLO) {
                            dataSendBuf.clear();
                            dataSendBuf.putShort((short) (sessionToken.length - DATA_MIN_LENGTH));
                            dataSendBuf.put(sessionToken);
                            dataSendBuf.flip();
                            writeAndUpdateState(dataChannel, dataSendBuf);
                        } else if (state == STATE_READY) {
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
                                int length = sizeToSend - DATA_MIN_LENGTH;
                                // We either (a) will do the MAC, or (b) are sending DataClientHello,
                                // which is known to exceed CONTROL_MIN_LENGTH.
                                ASSERT(length >= 0 && length <= Short.MAX_VALUE, "message length outside bounds");
                                dataSendBuf.putShort((short) length);
                                dataSendBuf.putLong(data.messageID);
                                dataSendBuf.put((byte) data.partID);
                                dataSendBuf.putShort((short) data.chunksSent++);
                                dataSendBuf.limit(dataSendBuf.position() + sizeToSend);
                                try {
                                    while (dataSendBuf.hasRemaining()) {
                                        data.channel.read(dataSendBuf);
                                    }
                                } catch (IOException e) {
                                    throw new FilesystemFailure(e);
                                }
                                // XXX only do for encrypted messages
                                dataSendBuf.position(LENGTH_SIZE);
                                crypto.encrypt(dataSendBuf, ~(dataClientCounter++ * 2));
                                dataSendBuf.rewind();
                                writeAndUpdateState(dataChannel, dataSendBuf);
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
                    }
                }
                if (state != STATE_SEND_HEL && state != STATE_SEND_LO &&
                        !controlSendBuf.hasRemaining() &&
                        messagesToSend.isEmpty() &&
                        (controlKey.interestOps() & OP_WRITE) != 0) {
                    controlKey.interestOps(OP_READ);
                } else if ((controlKey.interestOps() & OP_WRITE) == 0) {
                    controlKey.interestOps(OP_READ | OP_WRITE);
                }
                if (state != STATE_SEND_DATA_HELLO &&
                        !dataSendBuf.hasRemaining() &&
                        dataToSend.isEmpty() &&
                        (dataKey.interestOps() & OP_WRITE) != 0) {
                    dataKey.interestOps(OP_READ);
                } else if ((dataKey.interestOps() & OP_WRITE) == 0) {
                    dataKey.interestOps(OP_READ | OP_WRITE);
                }
            }
        } catch (BufferUnderflowException e) {
            failure = new ProtocolFailure("Message too short", e);
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
