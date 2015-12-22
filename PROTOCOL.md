Protocol specification
======================

In the future this protocol will be replaced with something based on CurveCP.
However, for the present the following suffices.

The protocol uses two ports, the control port 29192 and the data port 29292.
The data port is used for uploading/downloading large files (pictures,
videos, etc).  Appart from the initial exchange, everything is encrypted using
sodium's `crypto_box_easy`.

All messages are prepended by a u16 unencrypted size field.  This size is
offset by 1 (as in, a value of 5 would mean that the message is 6 bytes long).
The size excludes the size of the authentication tag (16 bytes). Note that the
translation from encoded size to actual size is uniform, so you must add the
size of the tag even if the message is not encrypted for the control channel,
and the size of the normal header for the data channel. For messages on the
data channel, the size also excludes the size of the header.  Messages on the
control channel are capped to a maximum size of 1024.

The type `key` represents a 32 byte public key.

Although the connection runs over TCP, the protocol is defined in terms of
packets, whose sizes are carefully chosen to prevent fragmentation. The sizes
are chosen under the assumption of IPv6; in practice IPv4 has lower overhead
and violation of the guaranteed IPv6 minimum MTU is very rare. The packet size
is:

    1280 guarenteed by IPv6
     - 16 for the two Mobile IPv6 headers
     - 20 for the fixed size TCP header
     - 34 for TCP SACK
     = 1210 bytes

The maximum message size

Encryption Negotiation
----------------------

    struct HelloClient {
        u16 version;
        u64 nonce1;
        key public_key;
    };

The `HelloClient` message is the first thing that the client must send on
creating the connection.

The version field contains the current version of the protocol. This version is
version 0.

Nonces are random 8 byte values. The final computed nonce for a message is
nonce1 followed by nonce2 followed by a message counter, where the client uses
even numbers and the server uses odd numbers. e.g. The third message the client
has sent this session has a counter of 4. The data channel has a separate
counter that functions the same except that it is bitwise negated.

The client transmits it's public key. This serves not only as a means of
encryption, it also identifies the user. The client is expected to already know
the server's public key.

    struct HelloServer {
        u8 error = 0;
        u64 nonce2;
        // begin encryption
        u8 session_token[32];
    };

The `HelloServer` is sent when the server receives a `HelloClient`, and the
server has accepted the connection. Now that the client has access to the other
half of the nonce, it can do encryption, and so the session token is encrypted.
The session token is required to open a data connection later. All further
communication on this channel is encrypted.

    struct ErrorBadVersion {
        u8 error = 1;
        u16 min_version;
        u16 max_version;
        u8 padding[12] = {};
    };

If the provided version is not acceptable, `ErrorBadVersion` is sent instead of
`HelloServer`, providing the permitted range of versions. The min and max are
inclusive. The server disconnects immediatly after sending this error message
(and indeed after all error messages).

    struct ErrorUnknownUser {
        u8 error = 2;
        u8 padding[16] = {};
    };

If the server does not know of the user, this is sent, and as mentioned above
the server disconnects.

Additionally, the server may disconnect immediately on a protocol violation
without sending a message.

    struct DataHelloClient {
        u8 session_token[32];
    };

At this point the client must open a data connection and send the
`DataHelloClient`. If the session token is invalid, the server closes the
connection. The server will not send any further messages until a valid data
connection has been established. All further communication on the data channel is
encrypted.

Once the data channel has been established, the server acts as if it has
received an implicit `RequestPeerInfoClient` message with the peer set to the
user.

Message Type Overview
---------------------

Type Client           Server
---- ------           ------
   0 RequestPeerInfo  PeerInfo
   1 UserInfo         Ack
   2                  DAck
   3 TextMessage      TextMessage
   4 PartMessage      PartMessage
   5 RequestPart
   6 Replay
   7 Exclusive        Exclusive
   8 Seen             Seen
   9 Typing           Typing
 128 ErrorUnknownPeer

Requesting User Information
---------------------------

    struct RequestPeerInfoClient {
        u8 type = 0;
        key peer;
    };

    struct PeerInfoServer {
        u8 type = 0;
        key peer;
        u8 subpeer;
        u32 color;
        u8 avatar_sha256[32];
	u32 avatar_size;
        u8 name[];
    };

    struct ErrorUnknownPeer {
        u8 type = 128;
	key peer;
    };

The name and color specify the saved values for the user's preferences.
Additionally, the SHA256 of the avatar file is provided so that the client can
decide to request it from the server if it is changed. The avatar can be
retrieved as a message part on the virtual message 2^63 - 1, with the part ID
specifying which subpeer to retreive the avatar for.

    struct UserInfoClient {
        u8 type = 1;
        u8 subuser;
        u32 color;
        u8 avatar_sha256[32];
	u32 avatar_size;
        u8 name[];
    };

Sent to the server to indicate a change in user info. If `avatar_sha256` or
`avatar_size` does not match the values stored on the server, the server will
respond by ACKing with message ID 2^63 - 1, after which the client must begin
transmitting the avatar over the data channel.

Sending Messages
----------------

    struct AckServer {
        u8 type = 1;
        u63 message_id;
        u63 timestamp;
    };

This is sent by the server in response to a message. The id is a unique number
identifying the message within a particular conversation. It starts at zero,
and IDs are assigned consecutively. The timestamp is a millisecond offset from
the Unix Epoch representing the time the server received the message. The type
`u63` indicates that the type is 64 bits wide, but the top bit must be clear.

    struct DAckServer {
        u8 type = 2;
        u63 message_id;
        u8 part;
    };

This is sent by the server in response to fully receiving a message part (in
the case of `BigText`, the whole message. For something like Photos, each photo
is a separate part). These must be sent in the order that the server finished
receiving the parts in.

    struct TextMessageClient {
        u8 type = 3;
        key peer;
        u8 subsender;
        u8 message[];
    };

Send a text message. The message contents are UTF-8 encoded. The message may
not have any leading or trailing whitespace. The message size is capped to
973, so that the server can deliver it to the peer without violating the 1024
byte total message length.

    struct PartMessageClient {
        u8 type = 4;
        key peer;
        u8 subsender;
        u8 part_type;
        u32 part_sizes[];
    };

Send a message whose data is large enough to require being sent over the data
channel. The number of parts is capped to 243 as per the same restriction as
for `TextMessage`. Most parts may not appear more than once per message. Parts
must not be sent before receiving acknowledgement of the message from the
server. There are currently two part types defined:

Send a large text message (`part_type = 0`). The message data is encoded the same
way as for a normal message.

Send photos (`part_type = 1`). The photos are encoded as JPEGs. This part may
appear multiple times in a message, though it may not be mixed with other part
types.

Receiving Messages
------------------

    struct TextMessageServer {
        u8 type = 3;
        key peer;
        b8 outgoing;
        u8 subsender;
        u63 message_id;
        u63 timestamp;
        u8 message[];
    };

Sent by the server to indicate a received message. The sender field is zero in
the case of a message from the peer, and one for messages sent by the user on a
different connection or in a past session.

    struct PartMessageServer {
        u8 type = 4;
	key peer;
	b8 outgoing;
        u8 subsender;
        u63 message_id;
        u63 timestamp;
        u8 part_type;
        u32 part_sizes[];
    };

Sent by the server to indicate a received part message. Unlike with sending
messages, the client must explicitly request the parts.

    struct PartialPart {
        u8 part_id;
        u16 chunk_begin;
    };

    struct RequestPartClient {
        u8 type = 5;
	key peer;
        u63 message_id;
        struct PartialPart parts[];
    };

Sent by the client to request (some subset of) the parts attached to a message.
The user's avatar is treated as part 255 of message 2^63 - 1. This cannot clash
with any real part, as there can only be 243 real parts. The peer's avatar is
treated as part 254 of the same message.

Querying Old Messages
---------------------

    struct ReplayClient {
        u8 type = 6;
	key peer;
        u63 begin;
        u63 end;
    };

Request the server to redeliver all messages between begin and end inclusive.
The given region may include message IDs that do not yet have a corresponding
message; such IDs are ignored. Messages are sent in order of decreasing message
ID. Parts are not sent; they must be requested. Only one replay can be in
progress at any one time; a new one cancels the previous one.

Status
------

    struct ExclusiveAny {
        u8 type = 7;
    };

Sent by the client, this indicates that they would like to close all other
clients for this user. Sent by the server, it is a request to close due to
another client requesting exclusivity. The connection is terminated immediately
following sending the notification. The client should close without any user
interaction. Client handling of connection loss due to other factors should
take into account that another client may request exclusivity while they are
disconnected.

    struct SeenAny {
        u8 type = 8;
	key peer;
        u63 message_id;
        u63 timestamp;
    };

Sent by the client, indicates that the client has seen the message at the given
time. Sent by the server, indicates that the peer has seen the message.

    struct TypingAny {
        u8 type = 9;
	key peer;
        u8 is_typing;
    };

Indicates whether the client or peer is currently typing.

Data Channel
------------

Large blobs of data -- photos, videos, etc -- are transmitted in chunks of
64KB. This allows more intelligent use of the available bandwidth by switching
away from a very large file to transfer waiting smaller files, creating the
illusion of faster transfer.

All messages use the same format, as follows:

    struct {
        u63 message_id;
        u8 part;
        u16 chunk;
        u8 data[];
    };

The part field is used for albums, to distinguish which photo this chunk
belongs to. The chunk field indicated which chunk this data is for. Chunks must
be sent in order, but chunks from different parts or messages may be
interleaved.
