package com.klkblake.mm;

/**
 * Created by kyle on 17/10/15.
 */
public interface SessionListener {
    void receivedMessage(int id, long timestamp, boolean author, String message);
    void receivedMessage(int id, long timestamp, boolean author, int numPhotos);

    void receivedPart(int messageID, int partID);

    void networkFailed(Throwable cause);
    void protocolViolation(String message);
    void filesystemFailed(Exception exception);

    void assertionFired(Exception assertion);
}
