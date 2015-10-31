Protocol specification
======================

The protocol uses two ports, the control port 29192 and the data port 29292.
The data port is used for uploading/downloading large files (pictures,
videos, etc).  Appart from the initial exchange, everything is encrypted using
NaCl's crypto_box.

Encryption Negotiation
----------------------

    struct ClientHel {
        u16 version;
        u64 nonce1;
        u8 public_key[32];
    };

The ClientHel message is the first thing that the client must send on creating
the connection.

The version field contains the current version of the protocol. This version is
version 0.

Nonces are random 8 byte values. The final computed nonce for a message is
nonce1 followed by nonce2 followed by a message counter, where the client uses
even numbers and the server uses odd numbers. e.g. The third message the client
has sent this session has a counter of 5.

The client transmits it's public key. This serves not only as a means of
encryption, it also identifies the user. The client is expected to already know
the server's public key.

    struct ServerHel {
        u8 error = 0;
        u64 nonce2;
        // begin encryption
        u8 session_token[32];
        u32 color;
        u8 avatar_sha256[32];
        u16 name_size;
        // end encryption
        // begin encryption
        u8 name[name_size];
        // end encryption
    };

The ServerHel is sent when the server receives a ClientHel, and the server has
accepted the connection. Now that the client has access to the other half of
the nonce, it can do encryption, and so the session token is encrypted. The
session token is required to open a data connection later. All further
communication on this channel is encrypted. The name and color specify the
saved values for the user's preferences. Additionally, the SHA256 of the avatar
file is provided so that the client can decide to request it from the server if
it is changed.

    struct ErrorBadVersion {
        u8 error = 1;
        u16 min_version;
        u16 max_version;
    };

If the provided version is not acceptable, ErrorBadVersion is sent instead of
ServerHel, providing the permitted range of versions. The min and max are
inclusive. The server disconnects immediatly after sending this error message
(and indeed after all error messages).

     struct ErrorUnknownUser {
         u8 error = 2;
     };

If the server does not know of the user, this is sent, and as mentioned above
the server disconnects.

Additionally, the server may disconnect immediately on a protocol violation
without sending a message.

    struct ClientDataHello {
        u8 session_token[32];
    };

At this point the client must open a data connection and send the
ClientDataHello. If the session token is invalid, the server closes the
connection. Otherwise it transmits a single zero byte as an ACK. All further
communication on the data channel is encrypted.

    struct ClientLo {
        u8 peer_public_key[32];
    };

Once the data connection is set up, the second part of the hello is sent. The
peer key indicates who the client wants to talk to.

    struct ServerLo {
        u8 error = 0;
        u32 color;
        u8 avatar_sha256[32];
        u16 name_size;
        // seperately encrypted
        u8 name[];
    };

Once the server is ready, it sends the settings for the peer.

     struct ErrorUnknownPeer {
         u8 error = 1;
     };

If the server does not know the peer, it sends an error and disconnects.

From this point, all messages are prepended by a u16 unencrypted size field.
This size is offset by 1 (as in, a value of 5 would mean that the message is 6
bytes long). The size excludes the size of the authentication tag
(crypto_box_MACBYTES). For messages on the data channel, the size also excludes
the size of the header. Messages on the control channel are capped to a maximum
size of 1024 (so encoded as 1023).

Message Type Overview
---------------------

Type Client      Server
---- ------      ------
   0             Ack
   1             DAck
   2 TextMessage TextMessage
   3 PartMessage PartMessage
   4 RequestPart
   5 Replay
   6 Exclusive   Exclusive
   7 Seen        Seen
   8 Typing      Typing
   9 Name        Name
  10 Color       Color
  11 Avatar      Avatar

Sending Messages
----------------

    struct AckServer {
        u8 type = 0;
        u63 message_id;
        u63 timestamp;
    };

This is sent by the server in response to a message.  The id is a unique number
identifying the message. It is incremented for every message. The timestamp is
a millisecond offset from the Unix Epoch representing the time the server
received the message. The type `u63` indicates that the type is 64 bits wide,
but the top bit must be clear.

    struct DAckServer {
        u8 type = 1;
        u63 message_id;
        u63 part;
    };

This is sent by the server in response to fully receiving a message part (in
the case of BigText, the whole message. For something like Photos, each
photo is a separate part).

    struct TextMessageClient {
        u8 type = 2;
        u8 message[];
    };

Send a text message. The message contents are UTF-8 encoded. The message may
not have any leading or trailing whitespace. The message size is capped to
1006, so that the server can deliver it to the peer without violating the 1024
byte total message length.

    struct PartMessageClient {
        u8 type = 3;
        u8 part_type;
        u32 part_sizes[];
    };

Send a message whose data is large enough to require being sent over the data
channel. The number of parts is capped to 251 as per the same restriction as
for TextMessage. Most parts may not appear more than once per message. Parts
must not be sent before receiving acknowledgement of the message from the
server. There are currently two part types defined:

Send a large text message (part_type = 0). The message data is encoded the same
way as for a normal message.

Send photos (part_type = 1). The photos are encoded as JPEGs. This part may
appear multiple times in a message, though it may not be mixed with other part
types.

Receiving Messages
------------------

    struct TextMessageServer {
        u8 type = 2;
        u8 sender;
        u63 message_id;
        u63 timestamp;
        u8 message[];
    };

Sent by the server to indicate a received message. The sender field is zero in
the case of a message from the peer, and one for messages sent by the user on a
different connection or in a past session.

    struct PartMessageServer {
        u8 type = 3;
        u8 sender;
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
        u8 type = 4;
        u63 message_id;
        struct PartialPart parts[];
    };

Sent by the client to request (some subset of) the parts attached to a message.
The user's avatar is treated as part 255 of message 2^63 - 1. This cannot clash
with any real part, as there can only be 251 real parts. The peer's avatar is
treated as part 254 of the same message.

Querying Old Messages
---------------------

    struct ReplayClient {
        u8 type = 5;
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
        u8 type = 6;
    };

Sent by the client, this indicates that they would like to close all other
clients for this user. Sent by the server, it is a request to close due to
another client requesting exclusivity. The connection is terminated immediately
following sending the notification. The client should close without any user
interaction. Client handling of connection loss due to other factors should
take into account that another client may request exclusivity while they are
disconnected.

    struct SeenAny {
        u8 type = 7;
        u63 message_id;
        u63 timestamp;
    };

Sent by the client, indicates that the client has seen the message at the given
time. Sent by the server, indicates that the peer has seen the message.

    struct TypingAny {
        u8 type = 8;
        u8 is_typing;
    };

Indicates whether the client or peer is currently typing.

User Preferences
----------------

    struct NameClient {
        u8 type = 9;
        u8 name[];
    };

Set the default name associated with the user.

    struct ColorClient {
        u8 type = 10;
        u8 red;
        u8 green;
        u8 blue;
    };

Set the color associated with the user.

    struct AvatarClient {
        u8 type = 11;
        u32 size;
    };

Set the avatar associated with the user. The avatar itself is sent over the
data channel; the client must wait for an ACK of message 2^63 - 1 before
beginning transmission.

    struct NameServer {
        u8 type = 9;
        u8 sender;
        u8 name[];
    };

Update the default name associated with the user. If sender is 1, then this is
the name associated with the peer.

    struct ColorServer {
        u8 type = 10;
        u8 sender;
        u8 red;
        u8 green;
        u8 blue;
    };

Update the color associated with the user.

    struct AvatarServer {
        u8 type = 11;
        u8 sender;
        u32 size;
    };

Update the avatar associated with the user. The avatar itself is sent over the
data channel; the client must wait for an ACK of message 2^63 - 1 before
beginning transmission.

Data Channel
------------

Large blobs of data -- photos, videos, etc -- are transmitted in chunks of
64KB. This allows more intelligent use of the available bandwidth by switching
away from a very large file to transfer waiting smaller files, creating the
illusion of faster transfer.

The data channel, unlike the control channel, uses a binary protocol after the
initial negotiation. All messages use the same format, as follows:

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
