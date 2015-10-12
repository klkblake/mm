Protocol specification
======================

The protocol uses two ports, the control port 29192 and the data port 29292.
The data port is used for uploading/downloading large files (high-res pictures,
videos, etc).  Appart from the initial exchange, everything is encrypted using
NaCl's crypto_box. The actual protocal is composed of messages which are
terminated by a '\n'. All binary data is transmitted in base64 -- the
decrypted exchange is entirely human-readable. All lengths, counts, and IDs are
8 digit hexadecimal numbers unless otherwise specified. Hexadeximal uses only
lowercase letters.

In the following, a '>' character indicates something sent to the server, and a
'<' indicates something received.

Encryption Negotiation
----------------------

The format of all messages on the control channel is as follows:

    <length> <message>

That is, a three digit decimal number denoting the length of the message,
followed by a space, followed by the message text, followed by a single newline
character. For clarity, the length prefix will not be shown in examples here.

    >HELLO <version> <nonce1>
    <HELLO <server public key> <nonce2> <session id>

The version field contains the protocol version as a zero-padded 4 digit number.
The connection ID is the value required to open the data port.

Nonces are random 8 byte values. The final computed nonce for a message is
nonce1 followed by nonce2 followed by a message counter, where the client uses
even numbers and the server uses odd numbers. e.g. The third message the client
has sent this session has a counter of 5. Past this point the text of every
message sent by the client is encrypted using NaCl's crypto_box. (That is, the

In the case of an unsupported version, the server may send:

    <ERROR BAD VERSION <min> <max>
    *server disconnects*

instead of HELLO, where min and max are the (inclusive) range of supported
versions. Additionally, the server may at any time send:

    <ERROR PROTOCOL VIOLATION
    *server disconnects*

if the client sends something bad. Both of these responses are followed by
immediate termination of the connection. Additionally, lower level violations
(or lack of server resources) may trigger immediate disconnection.

Now that the client knows who the server is, the client must tell the server
who they are and who they want to talk to.

    >I AM <client public key> TALKING TO <peer public key>

If the user is not recognised:

    <ERROR UNKNOWN <CLIENT/PEER> USER
    *server disconnects*

From this point on, the text of every message in either direction is encrypted.
Simultaneously, the client needs to open a data channel. The session ID is
simply sent in binary to initialise the data channel. A single zero byte is
sent in acknowlegement. Errors result in the data channel being silently
closed. The data channel must be opened and ready before any further commands
are sent.

Sending and Receiving Messages
------------------------------

    >TEXT <message contents>
    <ACK <id> <timestamp>

Send a text message. The message contents are UTF-8 encoded. The message
contents must not contain any C0 control characters except HT. The DEL
character is used to represent newlines. This provides an unambiguous
representation when displayed on most terminals. The message may not have any
leading or trailing whitespace. The maximum message length is 713; this is
dictated by the maximum length of a "YOUR TEXT" message. The id is a unique
number identifying the message. The timestamp is a 16-digit hexadecimal
millisecond offset from the Unix Epoch representing the time the server
received it.

    >BIGTEXT <size>
    <ACK <id> <timestamp>

Send a large text message The actual data for the message must be sent on the
data channel once the client receives the message ID.

    >PHOTOS <size> <size> <size>...
    <ACK <id> <timestamp>

Send photos. The photo is encoded as a JPEG of the specified size. The number
of photos is implicitly capped to 79 due to the cap on the encoded ciphertext
size.

    >AUDIO <size>
    <ACK <id> <timestamp>

Send a short audio recording. Format TBA.

    >VIDEO <size>
    <ACK <id> <timestamp>

Send a short video recording. Format TBA.

    <TEXT <id> <timestamp> <message contents>

    <BIGTEXT <id> <timestamp> <size>
    <PHOTOS <id> <timestamp> <size> <size> <size>...
    <AUDIO <id> <timestamp> <size>
    <VIDEO <id> <timestamp> <size>
    >ACK

Receiving a message works exactly the same way, except for which message
communicates the ID. The server may not begin transferring data on the data
channel until it has received acknowlegement of the message. Note that
acknowledgement must not be sent for TEXT messages, since there is no data
channel component.

    CANCEL <id> <part>

Instruct the recipient to cancel the transfer of the specified content. This
may be used due to lack of disk space or due to network congestion.

Querying Old Messages
---------------------

    >LAST <number>

Instruct the server to send to us the last number of messages. Messages sent to
us are delivered normally, messages sent by us are delivered as `YOUR TEXT`,
`YOUR PHOTO`, etc. Order of messages is undefined, but it is recommended that
the server deliver them in reverse order, as more recent messages are likely to
be more relevant.

    >AFTER <start id>

Similar to `LAST` but sends all messages since the given start ID inclusive.

    CONTENT <id> <part>
    CONTENT <id> <part> <chunk> <chunk> <chunk>...

Instructs the recipient to resend the given content for the given message.

    >PUSH
    >PULL

Whether the server should automatically push content over the data channel, or
wait until requested to do so by CONTENT. PUSH is the default.

Status
------

    >EXCLUSIVE

Signal that the user would like to close all other connections.

    <EXCLUSIVE
    *server disconnects*

One of the other connections requested exclusivity -- the client should close
without any user interaction.

    <DELIVERED <id> <timestamp>
    >ACK

Indicates that the message was delivered to the recipient's device.

    >SEEN <id> <timestamp>
    <ACK

    <SEEN <id> <timestamp>
    >ACK

Signal that the message has been seen at the given time.

    TYPING

    NOT TYPING

Signal that the user is currently typing or not, as appropriate.

User Preferences
----------------

    NAME <name>

Set the default name associated with a user. The name may be up to 32
characters long.

    COLOR <rrggbb>

Set the user's favourite color, in the standard hex format.

    AVATAR <size>

The user's photo is about to be transferred over the data channel as a JPEG.

    REMEMBER <name> <color> <avatar sha256>

Notify the server what the client believes the current values are, so that it
may update old ones. Should be used near the start of a connection. Only the
sha256 hash of the avatar is sent, in order to reduce bandwidth usage.

Data Channel
------------

Large blobs of data -- photos, videos, etc -- are transmitted in chunks of
64KB. This allows more intelligent use of the available bandwidth by switching
away from a very large file to transfer waiting smaller files, creating the
illusion of faster transfer.

The data channel, unlike the control channel, uses a binary protocol after the
initial negotiation. All messages use the same format, as follows:

    struct {
    	u16 length;
        union {
            u8 ciphertext[];
            struct {
                u32 message_id;
                u16 part;
                u16 chunk;
                u8 data[];
            };
        };
    };

The part field is used for albums, to distinguish which photo this chunk
belongs to. The chunk field indicated which chunk this data is for. The length
field is the length of the data field, not the whole message. The length should
have one added to it to get the actual length of the data field.

If the top bit of the part field is set, then this is an ACK for the specified
chunk, indicating that the data has been received and written to disk.

If the message id is zero and the part field is all ones (except for the top
bit), then this is the user's photo.
