use std::fmt::Debug;

/// A decoded pair of Minecraft lightmaps, handed over by `registerBlockState`'s neighbours on the
/// JVM side.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DeserializedLightData {
    pub sky_light: Box<[u8; 2048]>,
    pub block_light: Box<[u8; 2048]>,
}
