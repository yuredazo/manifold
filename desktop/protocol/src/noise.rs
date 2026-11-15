use crate::crypto::{KEY_LENGTH, KeyPair, is_weak_point};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Pattern {
    Xx,
    Ik,
}

impl Pattern {
    pub fn protocol_name(self) -> &'static str {
        match self {
            Pattern::Xx => "Noise_XX_25519_ChaChaPoly_SHA256",
            Pattern::Ik => "Noise_IK_25519_ChaChaPoly_SHA256",
        }
    }

    fn messages(self) -> usize {
        match self {
            Pattern::Xx => 3,
            Pattern::Ik => 2,
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct HandshakeError(pub &'static str);

pub struct TransportKeys {
    pub send: [u8; KEY_LENGTH],
    pub receive: [u8; KEY_LENGTH],
}

const MAX_MESSAGE: usize = 65535;

// snow does the Noise work. On top of it this refuses low-order keys, as the Android hub does, since snow lets them through.
pub struct Handshake {
    state: snow::HandshakeState,
    pattern: Pattern,
    initiator: bool,
    step: usize,
}

impl Handshake {
    pub fn new(pattern: Pattern, initiator: bool, local: &KeyPair, remote_static: Option<&[u8]>, prologue: &[u8]) -> Self {
        Self::build(pattern, initiator, local, remote_static, prologue, None)
    }

    pub fn with_fixed_ephemeral(pattern: Pattern, initiator: bool, local: &KeyPair, remote_static: Option<&[u8]>, prologue: &[u8], ephemeral: &[u8]) -> Self {
        Self::build(pattern, initiator, local, remote_static, prologue, Some(ephemeral))
    }

    fn build(pattern: Pattern, initiator: bool, local: &KeyPair, remote_static: Option<&[u8]>, prologue: &[u8], ephemeral: Option<&[u8]>) -> Self {
        let params = pattern.protocol_name().parse().expect("a known Noise protocol name");
        let mut builder = snow::Builder::new(params).local_private_key(&local.private).expect("a 32-byte key").prologue(prologue).expect("one prologue");
        if let Some(remote) = remote_static {
            builder = builder.remote_public_key(remote).expect("a 32-byte key");
        }
        if let Some(fixed) = ephemeral {
            builder = builder.fixed_ephemeral_key_for_testing_only(fixed);
        }
        let state = if initiator { builder.build_initiator() } else { builder.build_responder() }.expect("a complete handshake setup");
        Self { state, pattern, initiator, step: 0 }
    }

    pub fn complete(&self) -> bool {
        self.state.is_handshake_finished()
    }

    pub fn handshake_hash(&self) -> Vec<u8> {
        self.state.get_handshake_hash().to_vec()
    }

    pub fn remote_static(&self) -> Option<[u8; KEY_LENGTH]> {
        self.state.get_remote_static().and_then(|key| key.try_into().ok())
    }

    pub fn write_message(&mut self, payload: &[u8]) -> Result<Vec<u8>, HandshakeError> {
        let mut out = vec![0u8; MAX_MESSAGE];
        let length = self.state.write_message(payload, &mut out).map_err(|_| HandshakeError("could not write the handshake message"))?;
        out.truncate(length);
        self.step += 1;
        Ok(out)
    }

    pub fn read_message(&mut self, message: &[u8]) -> Result<Vec<u8>, HandshakeError> {
        // Every message here except the third of XX starts with the sender's ephemeral key.
        let starts_with_ephemeral = !(self.pattern == Pattern::Xx && self.step == 2);
        if starts_with_ephemeral && (message.len() < KEY_LENGTH || is_weak_point(&message[..KEY_LENGTH])) {
            return Err(HandshakeError("key agreement failed"));
        }
        let had_static = self.state.get_remote_static().is_some();
        let mut out = vec![0u8; MAX_MESSAGE];
        let length = self.state.read_message(message, &mut out).map_err(|_| HandshakeError("handshake message failed authentication"))?;
        if !had_static && self.state.get_remote_static().is_some_and(is_weak_point) {
            return Err(HandshakeError("key agreement failed"));
        }
        out.truncate(length);
        self.step += 1;
        Ok(out)
    }

    pub fn split(&mut self) -> TransportKeys {
        assert!(self.step == self.pattern.messages(), "the handshake is not finished");
        let (first, second) = self.state.dangerously_get_raw_split();
        if self.initiator { TransportKeys { send: first, receive: second } } else { TransportKeys { send: second, receive: first } }
    }
}
