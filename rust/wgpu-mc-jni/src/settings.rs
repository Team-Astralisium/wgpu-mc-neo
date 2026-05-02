#![allow(dead_code)]

use std::path::{Path, PathBuf};

use lazy_static::lazy_static;
use once_cell::sync::OnceCell;
use serde::{Deserialize, Serialize};
use strum::IntoEnumIterator;
use strum_macros::{EnumIter, IntoStaticStr};

use crate::RUN_DIRECTORY;

static RENDERER_CONFIG_JSON: OnceCell<PathBuf> = OnceCell::new();

/// Add your settings here. Only use the structs from this
/// file, like StringSetting, FloatSetting and IntSetting,
/// then add an appropriate field to SettingsInfo below,
/// and a default value in the Default impl for this.
///
/// TODO: handle the case of "json doesn't have every field",
/// because that's what happens when adding a new setting
#[derive(Serialize, Deserialize, Debug)]
#[serde(default)]
#[non_exhaustive]
pub struct Settings {
    pub backend: EnumSetting,
    pub vsync: BoolSetting,
}

#[derive(Serialize)]
pub struct SettingsInfo {
    backend: EnumSettingInfo<BackendSetting>,
    vsync: SettingInfo,
}

lazy_static! {
    pub static ref SETTINGS_INFO: SettingsInfo = SettingsInfo {
        backend: EnumSettingInfo::new(
            "Preferred wgpu backend. Changes apply after restart. Auto uses a platform default fallback chain.",
            true,
        ),
        vsync: SettingInfo {
            desc: "Whether or not to sync the framerate to the display's framerate.\
            May reduce screen tearing, on the cost of added latency.",
            needs_restart: true,
        },
    };
    pub static ref SETTINGS_INFO_JSON: String = serde_json::to_string(&*SETTINGS_INFO).unwrap();
}

impl Settings {
    /// Loads the settings from disk, or returns the defaults.
    pub fn load_or_default() -> Settings {
        let config_path = Self::config_path_get_or_init();
        let setting = if config_path.exists() {
            let contents = std::fs::read_to_string(config_path).unwrap_or_default();
            serde_json::from_str(&contents).unwrap_or_default()
        } else {
            let default = Settings::default();
            if !default.write() {
                log::warn!("Failed to persist default renderer settings to disk");
            }
            default
        };
        log::info!("Loaded settings: {setting:?}");
        setting
    }

    fn config_path_get_or_init<'a>() -> &'a PathBuf {
        RENDERER_CONFIG_JSON.get_or_init(|| {
            let mut path = RUN_DIRECTORY.get().unwrap().clone();
            let path_from_dot_minecraft = Path::new("config/wgpu-mc-renderer.json");
            path.push(path_from_dot_minecraft);
            path
        })
    }

    pub fn write(&self) -> bool {
        let config_path = Self::config_path_get_or_init();
        if let Some(parent) = config_path.parent() {
            if let Err(error) = std::fs::create_dir_all(parent) {
                log::error!("Couldn't create config directory at {parent:?}: {error}");
                return false;
            }
        }

        let json = match serde_json::to_string_pretty(self) {
            Ok(value) => value,
            Err(error) => {
                log::error!("Couldn't serialize wgpu renderer settings: {error}");
                return false;
            }
        };

        if let Err(error) = std::fs::write(config_path, json) {
            log::error!("Couldn't write wgpu-mc-renderer.json (config) to {config_path:?}: {error}");
            return false;
        }
        true
    }

    pub fn backend_selection(&self) -> BackendSetting {
        self.backend.get_variant::<BackendSetting>()
    }

    pub fn backend_candidates(&self) -> Vec<&'static str> {
        match self.backend_selection() {
            BackendSetting::Auto => {
                if cfg!(target_os = "windows") {
                    vec!["dx12", "vulkan", "gl"]
                } else if cfg!(target_os = "macos") {
                    vec!["metal", "vulkan", "gl"]
                } else {
                    vec!["vulkan", "gl"]
                }
            }
            BackendSetting::Dx12 => vec!["dx12"],
            BackendSetting::Vulkan => vec!["vulkan"],
            BackendSetting::Metal => vec!["metal"],
            BackendSetting::OpenGl => vec!["gl"],
        }
    }
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            backend: EnumSetting::from_variant(BackendSetting::Auto),
            vsync: BoolSetting { value: true },
        }
    }
}

#[derive(Serialize, Deserialize)]
pub struct SettingInfo {
    pub desc: &'static str,
    pub needs_restart: bool,
}

/// T should only be a c-like enum (no fields on variants),
/// mostly because I'm not sure what will happen when you put in anything else.
#[derive(Serialize, Deserialize)]
pub struct EnumSettingInfo<T: IntoEnumIterator + Into<&'static str>> {
    pub desc: &'static str,
    pub needs_restart: bool,
    variants: Vec<&'static str>,
    #[serde(skip_serializing)]
    _marker: std::marker::PhantomData<T>,
}

impl<T: IntoEnumIterator + Into<&'static str>> EnumSettingInfo<T> {
    pub fn new(desc: &'static str, needs_restart: bool) -> EnumSettingInfo<T> {
        EnumSettingInfo {
            desc,
            needs_restart,
            variants: T::iter().map(|e| e.into()).collect(),
            _marker: Default::default(),
        }
    }
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename = "bool")]
pub struct BoolSetting {
    pub value: bool,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename = "float")]
pub struct FloatSetting {
    min: f64,
    max: f64,
    step: f64,
    pub value: f64,
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

impl EnumSetting {
    pub fn from_variant<T: IntoEnumIterator + Eq>(variant: T) -> EnumSetting {
        EnumSetting {
            selected: T::iter().position(|item| item == variant).unwrap(),
        }
    }
    /// If you know which type the setting has, then just get the variant with this.
    pub fn get_variant<T: IntoEnumIterator>(&self) -> T {
        T::iter().nth(self.selected).unwrap()
    }
}

#[derive(EnumIter, IntoStaticStr, Eq, PartialEq, Copy, Clone, Debug)]
pub enum BackendSetting {
    Auto,
    Dx12,
    Vulkan,
    Metal,
    OpenGl,
}
