#![allow(dead_code)]

use std::path::PathBuf;

use lazy_static::lazy_static;
use once_cell::sync::OnceCell;
use serde::{Deserialize, Serialize};
use strum::IntoEnumIterator;
use strum_macros::{EnumIter, IntoStaticStr};

use crate::RUN_DIRECTORY;

static RENDERER_CONFIG_JSON: OnceCell<PathBuf> = OnceCell::new();

/// Renderer config, relative to the game directory.
const CONFIG_PATH: &str = "config/wgpu-mc-renderer.json";

/// Where the config used to live, before this crate was built for more than Fabric.
const LEGACY_CONFIG_PATH: &str = "config/fabric/wgpu-mc-renderer.json";

/// Add your settings here. Only use the structs from this
/// file, like FloatSetting and IntSetting, then add an
/// appropriate field to SettingsInfo below, and a default
/// value in the Default impl for this.
#[derive(Serialize, Deserialize, Debug)]
#[non_exhaustive]
pub struct Settings {
    /// Every field is `#[serde(default)]` so that a config file written by an older build still
    /// loads. Without this, adding a setting would reset the whole file, because
    /// [`Settings::load_or_default`] falls back to the defaults when deserialization fails.
    #[serde(default)]
    pub backend: EnumSetting,
    #[serde(default)]
    pub vsync: BoolSetting,
    /// Everything below is a debug switch, offered under the options screen's `Debug` heading.
    /// They are the marker files this renderer grew while it was being written, with a place in
    /// the UI: the marker still works (see [`crate::debug`]), and the setting is what a player can
    /// reach without knowing a file name.
    ///
    /// The ones that are off unless asked for name [`off`] as their serde default, because
    /// `#[serde(default)]` alone would take `BoolSetting::default()`, which is `true`.
    #[serde(default = "off")]
    pub gpu_based_validation: BoolSetting,
    #[serde(default = "off")]
    pub diagnostics: BoolSetting,
    #[serde(default)]
    pub bind_group_cache: BoolSetting,
    #[serde(default)]
    pub dynamic_offsets: BoolSetting,
    #[serde(default = "off")]
    pub trace_dynamic_offsets: BoolSetting,
    #[serde(default = "off")]
    pub dump_shaders: BoolSetting,
}

/// The default of a setting that is off unless a player asks for it.
///
/// `BoolSetting`'s own default is `true`, which is right for a switch that disables something and
/// wrong for a log.
fn off() -> BoolSetting {
    BoolSetting::of(false)
}

#[derive(Serialize)]
pub struct SettingsInfo {
    backend: EnumSettingInfo<GraphicsBackend>,
    vsync: SettingInfo,
    gpu_based_validation: SettingInfo,
    diagnostics: SettingInfo,
    bind_group_cache: SettingInfo,
    dynamic_offsets: SettingInfo,
    trace_dynamic_offsets: SettingInfo,
    dump_shaders: SettingInfo,
}

/// The section the options screen puts a setting under, when it is not one of the plain ones.
///
/// A name rather than an index, so the screen can decide how a section looks - it draws this one
/// as a sub-heading after a blank row - without this side knowing anything about layout.
const DEBUG_SECTION: &str = "Debug";

lazy_static! {
    pub static ref SETTINGS_INFO: SettingsInfo = SettingsInfo {
        backend: EnumSettingInfo::new(
            "Graphics API wgpu renders with. Vulkan is available on Windows and Linux, \
            DirectX 12 only on Windows. The two are not interchangeable at runtime: the wgpu \
            instance, the adapter and every resource below it are created for one backend and \
            live as long as the game does, so switching takes effect on the next launch.",
            true,
        ),
        vsync: SettingInfo {
            desc: "Whether or not to sync the framerate to the display's framerate.\
            May reduce screen tearing, on the cost of added latency. Takes effect as soon as it \
            is applied: the swapchain is reconfigured with the other present mode.",
            // Unlike `backend`, this is not a property of the wgpu instance: it only picks the
            // swapchain's present mode, and a surface can be reconfigured at any time. `sendSettings`
            // does exactly that, which is why this one is applied without a restart.
            needs_restart: false,
            section: None,
        },
        gpu_based_validation: SettingInfo::debug(
            "Ask the driver's own validation layer to check what the GPU is actually asked to do, \
            rather than only what wgpu was asked to record. It catches the mistakes host-side \
            validation cannot see - a resource read after it was freed, a shader reading past a \
            binding, a barrier in the wrong place - and reports them through the driver's debug \
            output. It costs performance and it is a development tool, so it is off by default. \
            The wgpu instance is created with this flag, so switching takes effect on the next \
            launch.",
            true,
        ),
        diagnostics: SettingInfo::debug(
            "Report what the renderer is doing: each pipeline the first time it is used, each \
            render pass, the draw counters once a second, and the processed shaders. Also the \
            switch that frames and textures are dumped through - the dump itself is still asked \
            for by a `wgpu-dump-now` file, because a dump is about one specific frame. This is \
            what the `wgpu-dump-frames` marker used to turn on; the marker still works.",
            false,
        ),
        bind_group_cache: SettingInfo::debug(
            "Reuse a bind group between draws that bind the same resources at different dynamic \
            offsets, instead of building one per draw. On by default: turning it off is how the \
            cache is ruled in or out as the cause of a rendering difference, at the cost of a \
            `wgpu::BindGroup` per draw. This is the `wgpu-no-bind-group-cache` marker as a \
            switch, and the marker still turns the cache off.",
            false,
        ),
        dynamic_offsets: SettingInfo::debug(
            "Bind uniform buffers with an offset instead of baking the offset into the bind group. \
            On by default, and it is what makes the bind group cache worth having: Minecraft \
            re-binds a buffer at a new offset for almost every draw. Turning it off bakes the \
            offset again, which is what the renderer did before dynamic offsets existed. This is \
            the `wgpu-no-dynamic-offsets` marker as a switch.",
            false,
        ),
        trace_dynamic_offsets: SettingInfo::debug(
            "Log every draw's bindings - the plan, each binding in slot order, and the offset that \
            travels with it - and the key the bind group cache was asked for. Very loud: it is a \
            line per draw, so it is meant to be turned on for a few frames and read back. This is \
            the `wgpu-trace-dynamic-offsets` marker as a switch.",
            false,
        ),
        dump_shaders: SettingInfo::debug(
            "Write the GLSL that reaches the shader compiler into `wgpu-shaders/`, after this \
            renderer's preprocessing. The source naga sees is not the source Minecraft ships - \
            uniforms are annotated with the binding the plan gave them, implicit blocks are added, \
            samplers are split - and it is the only place that shows which of those went wrong. \
            This is the `wgpu-dump-shaders` marker as a switch.",
            false,
        ),
    };
    pub static ref SETTINGS_INFO_JSON: String = serde_json::to_string(&*SETTINGS_INFO).unwrap();
}

/// The graphics API the wgpu instance is created with.
///
/// Only the backends this renderer has actually been exercised on are listed. `wgpu::Backends`
/// knows about Metal and the WebGPU backends too, but neither can present to a GLFW window on
/// the platforms this mod ships for, so offering them would only produce a launch that fails
/// after the window is already up.
#[derive(EnumIter, IntoStaticStr, Eq, PartialEq, Clone, Copy, Debug)]
pub enum GraphicsBackend {
    Vulkan,
    #[strum(serialize = "DirectX 12")]
    DirectX12,
}

impl Default for GraphicsBackend {
    fn default() -> Self {
        GraphicsBackend::Vulkan
    }
}

impl GraphicsBackend {
    /// Whether this backend can run on the platform the game is currently on.
    pub fn is_available_here(self) -> bool {
        match self {
            GraphicsBackend::Vulkan => cfg!(any(windows, target_os = "linux")),
            GraphicsBackend::DirectX12 => cfg!(windows),
        }
    }

    /// The backend to try when this one cannot be created.
    ///
    /// With two variants this is simply the other one. It exists as a named operation so that
    /// adding a backend to the enum forces a decision here instead of silently making the
    /// fallback order depend on declaration order.
    pub fn alternative(self) -> Self {
        match self {
            GraphicsBackend::Vulkan => GraphicsBackend::DirectX12,
            GraphicsBackend::DirectX12 => GraphicsBackend::Vulkan,
        }
    }
}

impl Settings {
    /// Loads the settings from disk, or returns the defaults.
    pub fn load_or_default() -> Settings {
        let config_path = Self::config_path_get_or_init();
        let setting = if config_path.exists() {
            let contents = std::fs::read_to_string(config_path).unwrap_or_default();
            match serde_json::from_str(&contents) {
                Ok(settings) => settings,
                Err(err) => {
                    // Every field has a serde default, so this really is a malformed file rather
                    // than an older one. Falling back keeps the game launchable.
                    log::warn!("Couldn't read {config_path:?} ({err}); using the defaults");
                    Settings::default()
                }
            }
        } else {
            let default = Settings::default();
            default.write();
            default
        };
        log::info!("Loaded settings: {setting:?}");
        setting
    }

    /// Where the renderer config lives, under the game directory.
    ///
    /// The `fabric/` subdirectory is a leftover from when this crate only shipped as a Fabric
    /// mod; NeoForge never creates it, so a fresh install would fail to write the config at all.
    /// A config left there by an older build is still read, and is rewritten to the new location
    /// the next time the options are applied.
    fn config_path_get_or_init<'a>() -> &'a PathBuf {
        RENDERER_CONFIG_JSON.get_or_init(|| {
            let run_directory = RUN_DIRECTORY.get().unwrap();
            let legacy = run_directory.join(LEGACY_CONFIG_PATH);
            if legacy.exists() {
                return legacy;
            }
            run_directory.join(CONFIG_PATH)
        })
    }

    pub fn write(&self) -> bool {
        let config_path = Self::config_path_get_or_init();

        if let Some(parent) = config_path.parent() {
            if let Err(err) = std::fs::create_dir_all(parent) {
                log::error!("Couldn't create {parent:?} for the renderer config: {err}");
                return false;
            }
        }

        let str = serde_json::to_string_pretty(self).unwrap();
        // Failing to persist a setting must not take the game down with it: a panic here would
        // run the panic hook, which exits Minecraft.
        match std::fs::write(config_path, str) {
            Ok(()) => true,
            Err(err) => {
                log::error!("Couldn't write the renderer config to {config_path:?}: {err}");
                false
            }
        }
    }
}

impl Settings {
    /// The graphics API to build the wgpu instance with, falling back to [`GraphicsBackend::default`]
    /// when the settings have not been loaded yet (the JVM sends the run directory during client
    /// setup, which happens before the window and therefore before `create_renderer`).
    pub fn graphics_backend(&self) -> GraphicsBackend {
        self.backend.get_variant()
    }
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            backend: EnumSetting::from_variant(GraphicsBackend::default()),
            vsync: BoolSetting::default(),
            // The debug switches default to the behaviour the renderer had before they existed:
            // logging and tracing off, the bind group cache and dynamic offsets on, and GPU-based
            // validation off - it used to be unconditional, which cost every player the driver's
            // slowest validation path.
            gpu_based_validation: BoolSetting::of(false),
            diagnostics: BoolSetting::of(false),
            bind_group_cache: BoolSetting::of(true),
            dynamic_offsets: BoolSetting::of(true),
            trace_dynamic_offsets: BoolSetting::of(false),
            dump_shaders: BoolSetting::of(false),
        }
    }
}

/// Every debug switch the renderer has, resolved the way [`crate::debug`] reads them.
///
/// A struct rather than six getters because the flags are always wanted together: they are applied
/// in one place (when the settings are loaded or sent) and read in another (the draw path).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DebugSettings {
    pub gpu_based_validation: bool,
    pub diagnostics: bool,
    pub bind_group_cache: bool,
    pub dynamic_offsets: bool,
    pub trace_dynamic_offsets: bool,
    pub dump_shaders: bool,
}

impl Settings {
    pub fn debug(&self) -> DebugSettings {
        DebugSettings {
            gpu_based_validation: self.gpu_based_validation.value,
            diagnostics: self.diagnostics.value,
            bind_group_cache: self.bind_group_cache.value,
            dynamic_offsets: self.dynamic_offsets.value,
            trace_dynamic_offsets: self.trace_dynamic_offsets.value,
            dump_shaders: self.dump_shaders.value,
        }
    }
}

#[derive(Serialize, Deserialize)]
pub struct SettingInfo {
    pub desc: &'static str,
    pub needs_restart: bool,
    /// Which section of the options screen this belongs to; absent for the plain ones.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub section: Option<&'static str>,
}

impl SettingInfo {
    /// A setting that sits in the options screen's list without a heading of its own.
    pub const fn new(desc: &'static str, needs_restart: bool) -> SettingInfo {
        SettingInfo {
            desc,
            needs_restart,
            section: None,
        }
    }

    /// A setting under the options screen's `Debug` sub-heading.
    pub const fn debug(desc: &'static str, needs_restart: bool) -> SettingInfo {
        SettingInfo {
            desc,
            needs_restart,
            section: Some(DEBUG_SECTION),
        }
    }
}

/// T should only be a c-like enum (no fields on variants),
/// mostly because I'm not sure what will happen when you put in anything else.
#[derive(Serialize, Deserialize)]
pub struct EnumSettingInfo<T: IntoEnumIterator + Into<&'static str> + LanguageKey> {
    pub desc: &'static str,
    pub needs_restart: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub section: Option<&'static str>,
    /// The display names, in the order the options screen cycles through them.
    variants: Vec<&'static str>,
    /// Where each of those names is translated, in the same order.
    ///
    /// A key rather than a translated string because the wording belongs to the language files; the
    /// display name above is what a language that has not heard of this setting falls back to.
    variant_keys: Vec<&'static str>,
    #[serde(skip_serializing)]
    _marker: std::marker::PhantomData<T>,
}

impl<T: IntoEnumIterator + Into<&'static str> + LanguageKey> EnumSettingInfo<T> {
    pub fn new(desc: &'static str, needs_restart: bool) -> EnumSettingInfo<T> {
        EnumSettingInfo {
            desc,
            needs_restart,
            section: None,
            variants: T::iter().map(|e| e.into()).collect(),
            variant_keys: T::iter().map(|e| e.lang_key()).collect(),
            _marker: Default::default(),
        }
    }
}

/// A setting value that is named in the mod's language file.
///
/// The two halves of a value's text travel together: the name the schema carries, which is what the
/// options screen shows when nothing translates it, and the key a translation is found under.
pub trait LanguageKey {
    fn lang_key(&self) -> &'static str;
}

impl LanguageKey for GraphicsBackend {
    fn lang_key(&self) -> &'static str {
        match self {
            GraphicsBackend::Vulkan => "wgpu_mc.option.backend.vulkan",
            GraphicsBackend::DirectX12 => "wgpu_mc.option.backend.directx12",
        }
    }
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename = "bool")]
pub struct BoolSetting {
    pub value: bool,
}

impl BoolSetting {
    /// A setting whose default is not `true`, which is what [`Default`] gives every bool.
    pub const fn of(value: bool) -> BoolSetting {
        BoolSetting { value }
    }
}

impl Default for BoolSetting {
    fn default() -> Self {
        BoolSetting { value: true }
    }
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename = "float")]
pub struct FloatSetting {
    min: f64,
    max: f64,
    step: f64,
    pub value: f64,
}

impl Default for FloatSetting {
    fn default() -> Self {
        FloatSetting {
            min: 70.0,
            max: 120.0,
            step: 2.5,
            value: 90.0,
        }
    }
}

impl FloatSetting {
    pub fn get_min(&self) -> f64 {
        self.min
    }

    pub fn get_step(&self) -> f64 {
        self.step
    }

    pub fn get_max(&self) -> f64 {
        self.max
    }
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename = "int")]
pub struct IntSetting {
    min: i32,
    max: i32,
    step: i32,
    pub value: i32,
}

impl Default for IntSetting {
    fn default() -> Self {
        IntSetting {
            min: 0,
            max: 100,
            step: 1,
            value: 0,
        }
    }
}

impl IntSetting {
    pub fn get_min(&self) -> i32 {
        self.min
    }

    pub fn get_step(&self) -> i32 {
        self.step
    }

    pub fn get_max(&self) -> i32 {
        self.max
    }
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename = "enum")]
pub struct EnumSetting {
    pub selected: usize,
}

impl Default for EnumSetting {
    fn default() -> Self {
        EnumSetting { selected: 0 }
    }
}

impl EnumSetting {
    pub fn from_variant<T: IntoEnumIterator + Eq>(variant: T) -> EnumSetting {
        EnumSetting {
            selected: T::iter().position(|item| item == variant).unwrap(),
        }
    }
    /// If you know which type the setting has, then just get the variant with this.
    ///
    /// A `selected` index that does not name a variant (a hand-edited config file, or a config
    /// written by a build that offered more variants) falls back to `T::default()` rather than
    /// panicking: a panic here would run the panic hook, which exits the game.
    pub fn get_variant<T: IntoEnumIterator + Default>(&self) -> T {
        T::iter().nth(self.selected).unwrap_or_default()
    }
}

/// The JSON shapes below are a contract with the options screen, which builds its widgets from
/// `SETTINGS_INFO_JSON` and sends the edited values back through `sendSettings`. They are the part
/// of the graphics-backend switch that can be checked without a GPU, so they are.
#[cfg(test)]
mod tests {
    use super::*;

    /// The config file as it looks without a `backend` key, i.e. one written before the setting
    /// existed. It has to keep loading, or adding the setting would reset everyone's options.
    const LEGACY_CONFIG: &str = r#"{
        "vsync": { "type": "bool", "value": false }
    }"#;

    #[test]
    fn a_config_without_a_backend_key_still_loads() {
        let settings: Settings = serde_json::from_str(LEGACY_CONFIG).expect("legacy config");

        assert_eq!(settings.graphics_backend(), GraphicsBackend::Vulkan);
        assert!(!settings.vsync.value, "the existing value must survive");

        // A config written before the debug switches existed has none of them, and every one of
        // them has to come back as its default rather than as `false` - `bind group cache` and
        // `dynamic offsets` default to *on*.
        let debug = settings.debug();
        assert!(debug.bind_group_cache, "an absent switch keeps its default");
        assert!(debug.dynamic_offsets, "an absent switch keeps its default");
        assert!(!debug.diagnostics);
        assert!(!debug.gpu_based_validation);
    }

    #[test]
    fn every_value_of_an_enum_setting_names_a_translation_key() {
        let info: serde_json::Value = serde_json::from_str(&SETTINGS_INFO_JSON).expect("schema");
        let backend = &info["backend"];

        let variants = backend["variants"].as_array().expect("variants");
        let keys = backend["variant_keys"].as_array().expect("variant_keys");

        assert_eq!(
            keys.len(),
            variants.len(),
            "one key per value: the options screen shows them in this order"
        );

        for key in keys {
            let key = key.as_str().expect("a string key");
            assert!(
                key.starts_with("wgpu_mc.option.backend."),
                "{key} is not a key of this mod's settings namespace"
            );
        }

        assert_eq!(
            keys[1],
            serde_json::json!("wgpu_mc.option.backend.directx12"),
            "the second value is DirectX 12, which is what the display name says"
        );
    }

    #[test]
    fn the_debug_switches_are_offered_under_a_heading() {        let info: serde_json::Value = serde_json::from_str(&SETTINGS_INFO_JSON).expect("schema");

        for name in [
            "gpu_based_validation",
            "diagnostics",
            "bind_group_cache",
            "dynamic_offsets",
            "trace_dynamic_offsets",
            "dump_shaders",
        ] {
            assert_eq!(
                info[name]["section"],
                serde_json::json!("Debug"),
                "{name} is a debug switch and belongs under the heading"
            );
        }

        // The two that are not debug switches carry no section, or the options screen would draw
        // the heading over them.
        assert!(info["backend"].get("section").is_none());
        assert!(info["vsync"].get("section").is_none());
    }

    /// The language files, pulled in so that editing one of them re-runs these tests.
    ///
    /// The options screen builds every key it asks for out of a setting's name - `wgpu_mc.option.`
    /// and, for the description, `.tooltip` - so a setting the language files have never heard of
    /// is not an error anywhere: it is a row with a name made out of the config key. That is worth
    /// a test rather than a review, because adding a setting is exactly when it happens.
    const EN_US: &str =
        include_str!("../../../neoforge/src/main/resources/assets/wgpu_mc/lang/en_us.json");
    const ZH_CN: &str =
        include_str!("../../../neoforge/src/main/resources/assets/wgpu_mc/lang/zh_cn.json");

    fn translations(json: &str) -> serde_json::Value {
        serde_json::from_str(json).expect("a language file")
    }

    /// The settings the schema offers, in the order the options screen shows them.
    fn setting_names(info: &serde_json::Value) -> Vec<String> {
        info.as_object()
            .expect("the schema is an object")
            .keys()
            .cloned()
            .collect()
    }

    #[test]
    fn every_setting_has_a_name_in_every_language() {
        let info: serde_json::Value = serde_json::from_str(&SETTINGS_INFO_JSON).expect("schema");
        let languages = [("en_us", translations(EN_US)), ("zh_cn", translations(ZH_CN))];

        for (language, translations) in &languages {
            for setting in setting_names(&info) {
                let key = format!("wgpu_mc.option.{setting}");

                assert!(
                    translations.get(&key).is_some(),
                    "{language} has no name for `{setting}` ({key})"
                );
            }
        }
    }

    #[test]
    fn every_value_of_an_enum_setting_has_a_name_in_every_language() {
        let info: serde_json::Value = serde_json::from_str(&SETTINGS_INFO_JSON).expect("schema");
        let languages = [("en_us", translations(EN_US)), ("zh_cn", translations(ZH_CN))];

        for (language, translations) in &languages {
            for setting in setting_names(&info) {
                let Some(keys) = info[&setting]["variant_keys"].as_array() else {
                    continue;
                };

                for key in keys {
                    let key = key.as_str().expect("a string key");

                    assert!(
                        translations.get(key).is_some(),
                        "{language} has no name for `{setting}` value {key}"
                    );
                }
            }
        }
    }

    #[test]
    fn a_translation_only_has_keys_this_mod_owns() {
        // A key that names nothing is a typo that would show up as a missing translation somewhere
        // else - usually a whole section of the screen left in English - so the namespace is
        // checked from this side, where the settings are.
        for (language, translations) in [("en_us", translations(EN_US)), ("zh_cn", translations(ZH_CN))] {
            for key in translations.as_object().expect("an object").keys() {
                assert!(
                    key.starts_with("wgpu_mc."),
                    "{language} translates {key}, which is not this mod's key"
                );
            }
        }
    }

    #[test]
    fn only_gpu_based_validation_needs_a_restart() {        let info: serde_json::Value = serde_json::from_str(&SETTINGS_INFO_JSON).expect("schema");

        // It is an instance flag, and the instance is created once. The rest are read on the draw
        // path, so applying them takes effect on the next frame.
        assert_eq!(
            info["gpu_based_validation"]["needs_restart"],
            serde_json::Value::Bool(true)
        );

        for name in [
            "diagnostics",
            "bind_group_cache",
            "dynamic_offsets",
            "trace_dynamic_offsets",
            "dump_shaders",
        ] {
            assert_eq!(
                info[name]["needs_restart"],
                serde_json::Value::Bool(false),
                "{name} applies without a restart"
            );
        }
    }

    #[test]
    fn the_debug_defaults_are_what_the_renderer_did_before_they_existed() {
        let debug = Settings::default().debug();

        assert!(!debug.diagnostics, "logging was off");
        assert!(!debug.trace_dynamic_offsets, "tracing was off");
        assert!(!debug.dump_shaders, "shader dumps were off");
        assert!(debug.bind_group_cache, "the cache was on");
        assert!(debug.dynamic_offsets, "dynamic offsets were on");
        assert!(
            !debug.gpu_based_validation,
            "GPU-based validation is a development tool, not a default"
        );
    }

    #[test]
    fn the_selected_index_names_the_backend() {
        let mut settings = Settings::default();

        settings.backend = EnumSetting::from_variant(GraphicsBackend::DirectX12);
        assert_eq!(settings.graphics_backend(), GraphicsBackend::DirectX12);

        // What Gson writes back after the options screen edited the enum.
        let round_tripped: Settings =
            serde_json::from_str(&serde_json::to_string(&settings).unwrap()).expect("round trip");
        assert_eq!(round_tripped.graphics_backend(), GraphicsBackend::DirectX12);
    }

    #[test]
    fn an_out_of_range_index_falls_back_instead_of_panicking() {
        let settings: Settings = serde_json::from_str(
            r#"{ "backend": { "type": "enum", "selected": 7 } }"#,
        )
        .expect("config with a bogus index");

        assert_eq!(settings.graphics_backend(), GraphicsBackend::Vulkan);
    }

    #[test]
    fn the_schema_marks_the_backend_as_needing_a_restart() {
        let info: serde_json::Value = serde_json::from_str(&SETTINGS_INFO_JSON).expect("schema");
        let backend = &info["backend"];

        assert_eq!(backend["needs_restart"], serde_json::Value::Bool(true));
        assert_eq!(
            backend["variants"],
            serde_json::json!(["Vulkan", "DirectX 12"]),
            "the options screen renders these verbatim"
        );
    }
}
