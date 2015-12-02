package com.klkblake.mm.common;

/**
 * Created by kyle on 17/10/15.
 */
public interface SessionListener {
    void receivedOurColor(int color);
    void receivedOurName(String name);
    void receivedOurAvatarSha256(byte[] avatarSha256);

    void receivedPeerColor(int color);
    void receivedPeerName(String name);
    void receivedPeerAvatarSha256(byte[] avatarSha256);

    void receivedMessage(long id, long timestamp, boolean author, String message);
    void receivedMessage(long id, long timestamp, boolean author, int numPhotos);

    void receivedPart(long messageID, int partID);

    void badVersion(int minVersion, int maxVersion);
    void unknownUser();
    void unknownPeer();
    void authenticationFailed(boolean isControlChannel);
    void networkFailed(Throwable cause);
    void protocolViolation(String message, Throwable cause);
    void filesystemFailed(Exception exception);

    void assertionFired(Exception assertion);
}
