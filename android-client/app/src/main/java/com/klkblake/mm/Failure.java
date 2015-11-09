package com.klkblake.mm;

/**
 * Created by kyle on 10/11/15.
 */
abstract class Failure extends Exception {
    public Failure(String message) {
        super(message);
    }

    public Failure(Throwable cause) {
        super(cause);
    }

    public abstract void notifyListener(SessionListener listener);

    static class ProtocolFailure extends Failure {
        public ProtocolFailure(String message) {
            super(message);
        }

        @Override
        public void notifyListener(SessionListener listener) {
            listener.protocolViolation(getMessage());
        }
    }

    static class FilesystemFailure extends Failure {
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

    static class SocketFailure extends Failure {
        public SocketFailure(Throwable cause) {
            super(cause);
        }

        @Override
        public void notifyListener(SessionListener listener) {
            listener.networkFailed(getCause());
        }
    }

    static class AssertionFailure extends Failure {
        public AssertionFailure(String message) {
            super(message);
        }

        @Override
        public void notifyListener(SessionListener listener) {
            listener.assertionFired(this);
        }
    }
}
