# wgpu-mc NeoForge (Initial Port)

This module is the first-stage NeoForge port of the Fabric `wgpu_mc` mod.

## Current Baseline

- Loader: NeoForge `21.1.228`
- Minecraft: `1.21.1`
- Java: `21`
- Native bridge: reuses `rust/wgpu-mc-jni` through `rustImport`

## Port Scope in This Stage

- Rebased metadata and package naming to `wgpu_mc` / `dev.birb.wgpu`
- Wired NeoForge client lifecycle:
  - native library bootstrap
  - client resource reload listener registration
- Rewired Video Options entry point to a NeoForge-side `OptionPageScreen`
- Ported custom option GUI stack:
  - `OptionPageScreen`, `OptionPages`, `WidgetRenderer`
  - option model types (`BoolOption`, `IntOption`, `FloatOption`, enum option variants)
  - custom widget controls and tooltips
- Migrated JNI-facing Java classes that must keep original names:
  - `dev.birb.wgpu.rust.WgpuNative`
  - `dev.birb.wgpu.rust.WgpuResourceProvider`
  - `dev.birb.wgpu.render.Wgpu`
- Brought over shader assets under `assets/wgpu_mc`

## Known Gaps 

- Fabric target is `25w17a` (1.21.6 snapshot lineage), NeoForge target is `1.21.1` stable.
- Deobfuscations that Fabric mixins are not yet remapped from Yarn names to Mojmap names.
- Parts of the access Widener rules are not yet translated to NeoForge access transformers.
- Rendering pipeline hooks still include scaffold-level mixins that need deeper NeoForge-side integration.
