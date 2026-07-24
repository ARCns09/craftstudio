# CraftStudio Product Requirements Document

**Product name:** CraftStudio  
**Product type:** Client-side Minecraft resource-pack creation and inspection mod  
**Initial platform:** Fabric  
**Target game version:** Minecraft: Java Edition 1.21.11  
**Primary language:** Java 21  
**Initial mod ID:** `craftstudio`  
**Recommended base package:** `dev.arcn.craftstudio`  
**Document status:** Build-ready product and engineering specification  
**PRD version:** 1.0  
**Last updated:** 2026-07-24

---

## 0. AI Implementation Directive

This document is written so an AI coding agent can implement CraftStudio in small, verifiable stages without repeatedly redesigning the project.

The implementation agent must follow these rules:

1. Do not build every feature at once.
2. Complete milestones in the order defined in this PRD.
3. Keep the mod client-side only.
4. Target only Minecraft 1.21.11 for the first release line.
5. Use Java, not Kotlin, for the initial implementation.
6. Use the versions generated or recommended by the Fabric Template Generator for Minecraft 1.21.11. Do not guess dependency versions.
7. Keep all Minecraft and Fabric API interactions behind adapter classes where practical.
8. Keep domain logic independent from screen widgets and Minecraft rendering classes.
9. Use one asset graph as the source of truth for browsing, dependency resolution, preview, validation, and export.
10. Never hardcode a list of textures for specific vanilla blocks as the primary implementation strategy.
11. Resolve blockstates, models, parent models, texture variables, item definitions, item models, and atlas constraints from the actual 1.21.11 assets.
12. Every milestone must compile and run before beginning the next milestone.
13. Every file operation must be safe, validated, and restricted to CraftStudio-owned or user-selected paths.
14. Never delete or overwrite user files without a backup or explicit confirmation.
15. Prefer a smaller correct implementation over a broad incomplete one.

Any class names in this PRD that belong to CraftStudio are normative recommendations. Names of Minecraft or Fabric classes are conceptual unless verified against the exact mappings generated for the project.

---

# 1. Executive Summary

CraftStudio is a visual resource-pack development environment built directly into Minecraft.

A user presses **F8**, creates or opens a project, browses Minecraft assets visually, selects a block, item, GUI element, sound, font, model, or texture, and adds it to a resource-pack project. CraftStudio discovers the complete dependency bundle required by that asset, previews the resolved result, validates the pack, reloads changes, and exports a clean resource pack.

The product is not merely a texture copier. Its core intelligence is the **Minecraft Asset Graph**, a structured representation of how Minecraft assets depend on one another.

For example, selecting a furnace may resolve:

```text
minecraft:furnace
├── blockstate definition
├── unlit model variants
├── lit model variants
├── parent models
├── front texture
├── lit front texture
├── side texture
├── top texture
├── client item definition
├── item model
└── atlas requirements
```

The same graph powers:

- Asset browsing
- Complete bundle extraction
- Shared dependency detection
- 3D preview
- Missing-file diagnostics
- Export
- Future pack conversion
- Future model editing

The initial product focuses on creating a correct project, selecting vanilla assets, resolving dependencies, previewing blocks and items, validating the project, and exporting a working pack.

---

# 2. Product Vision

## 2.1 Vision statement

> CraftStudio is the visual IDE for Minecraft resource packs.

It should give resource-pack creators the same confidence that a code editor gives developers:

- The project structure is generated correctly.
- Dependencies are visible.
- Errors are explained.
- Changes can be previewed.
- The final output can be built reliably.

## 2.2 Product promise

> Select what you want to customize, receive every required asset, edit it with your preferred tools, preview the result, detect errors, and export a clean Minecraft resource pack.

## 2.3 Product personality

CraftStudio should feel:

- Modern
- Fast
- Visual
- Beginner-friendly
- Technically trustworthy
- Native to Minecraft
- Powerful without looking intimidating

It should not feel like a debug menu, a generic file browser, or a desktop editor squeezed into a Minecraft screen.

---

# 3. Problem Statement

Creating a Minecraft resource pack currently requires creators to understand several fragmented systems:

- Locating the correct Minecraft JAR
- Extracting vanilla assets
- Remembering resource-pack folder paths
- Understanding `pack.mcmeta`
- Discovering which textures a model uses
- Understanding blockstate variants and multipart definitions
- Resolving parent models
- Understanding texture variables such as `#side`
- Finding client item definitions
- Understanding item model dispatch
- Keeping textures in valid atlases
- Reloading resources after edits
- Diagnosing purple-and-black missing textures
- Zipping the correct folder level
- Updating pack format values between versions

Simple-looking objects often use multiple files. A crafting table, furnace, door, bed, chest, redstone component, crop, stair, fence, item with states, or animated texture can depend on a small tree of assets.

Existing file-based workflows expose implementation details before the user even starts creating.

CraftStudio solves this by presenting Minecraft objects first and file paths second.

---

# 4. Goals

## 4.1 Primary goals

1. Let users create a valid resource-pack project from inside Minecraft.
2. Let users browse vanilla assets visually.
3. Resolve all standard dependencies required to render selected blocks and items.
4. Display correct textures on a trustworthy block and item preview.
5. Let users choose complete, unique-only, or custom dependency extraction.
6. Preserve correct directory structure automatically.
7. Validate missing, malformed, or inconsistent assets.
8. Export a valid folder or ZIP resource pack.
9. Reload project changes without restarting Minecraft.
10. Keep the first implementation maintainable and version-specific.

## 4.2 Secondary goals

1. Integrate with external image and model editors.
2. Support GUI, sound, font, particle, painting, and environment asset categories.
3. Detect Prism Launcher instances and install projects directly.
4. Provide project history and backup tools.
5. Prepare architecture for future version adapters.

## 4.3 Success definition for the first public release

A beginner can:

1. Install CraftStudio.
2. Press F8.
3. Create a project.
4. Search for “furnace.”
5. Add the complete furnace asset bundle.
6. Open `furnace_front.png` in an external editor.
7. Save an edited texture.
8. Reload or auto-reload the project.
9. View the edited furnace in the 3D preview.
10. Export the project as a ZIP.
11. Enable that ZIP as a resource pack in Minecraft 1.21.11.
12. See the edited furnace with no missing textures.

No manual JAR extraction should be required.

---

# 5. Non-Goals

The following are explicitly outside the first release:

- A full pixel-art editor
- A full Blockbench replacement
- A general-purpose JSON IDE
- Server-side features
- Multiplayer synchronization
- Cloud accounts
- Online project storage
- A community marketplace
- Automatic conversion for every historical Minecraft version
- Shader-pack authoring
- Data-pack authoring
- OptiFine-specific features
- CIT Resewn authoring
- Entity model replacement systems from other mods
- Automatic copyright checking
- Fabric, NeoForge, and Quilt support in one initial codebase
- Supporting every Minecraft version
- Editing game JAR files
- Modifying active third-party resource packs without importing them as a project

These may be revisited later, but none may delay the core release.

---

# 6. Target Users

## 6.1 Beginner texture creator

Needs:

- Correct files without learning paths
- Clear previews
- Safe restore-to-vanilla actions
- Friendly errors
- One-click export

## 6.2 Experienced resource-pack creator

Needs:

- Exact dependency visibility
- Custom selection
- Shared dependency controls
- Fast search
- Open-in-editor actions
- Validation
- Efficient reloads
- Advanced path information

## 6.3 Modpack creator

Needs:

- Small focused resource packs
- Pack validation
- Easy export
- Compatibility awareness
- Fast testing across instances

## 6.4 YouTuber, builder, or server artist

Needs:

- GUI and block previews
- Simple asset replacement
- Reliable pack installation
- Low setup time

---

# 7. Core User Stories

## 7.1 Project creation

As a creator, I want to create a resource-pack project so that CraftStudio generates the correct metadata and directory structure.

## 7.2 Complete block extraction

As a creator, I want to select one block and receive every required model and texture so that the exported result works in all supported states.

## 7.3 Shared dependency awareness

As an advanced creator, I want CraftStudio to distinguish unique dependencies from shared vanilla dependencies so that my pack remains clean.

## 7.4 Correct preview

As a creator, I want the preview to use the same resolved models and textures as export so that the preview is trustworthy.

## 7.5 External editing

As a creator, I want to open a texture in Krita, GIMP, Aseprite, or my system editor so that CraftStudio does not need to become an image editor.

## 7.6 Validation

As a creator, I want errors to identify the exact broken file and dependency chain so that I can fix problems quickly.

## 7.7 Safe export

As a creator, I want to export a ZIP or folder without incorrect nesting so that Minecraft recognizes the pack.

---

# 8. Technical Baseline

## 8.1 Supported game version

The initial release targets:

```text
Minecraft: Java Edition 1.21.11
```

This is the final release in the 1.21.x version line before the 26.x versioning transition.

CraftStudio 1.x must not claim compatibility with 26.1, 26.1.1, 26.2, or later unless a dedicated port is completed and tested.

## 8.2 Loader and toolchain

Use:

- Fabric Loader
- Fabric API
- Fabric Loom
- Java 21
- Gradle wrapper generated by the official Fabric template
- The mapping configuration produced by the template

For Minecraft 1.21.11, Loom must use the remapping workflow appropriate for an obfuscated Minecraft version. Do not copy a Loom plugin configuration from a 26.x template.

## 8.3 Template Generator selections

Recommended initial selections:

```text
Mod Name: CraftStudio
Mod ID: craftstudio
Package Name: dev.arcn.craftstudio
Minecraft Version: 1.21.11
Language: Java
Data Generation: Off
Split client and common sources: On
Kotlin: Off
Kotlin build script: Off
```

Why split client and common sources:

- CraftStudio is client-side.
- Rendering, screens, keybindings, and the active game client belong in client sources.
- Shared data records and pure Java utilities may remain in common sources.
- The split reduces accidental dedicated-server class loading.

## 8.4 Resource-pack baseline

Minecraft 1.21.11 uses resource-pack version **75.0**.

The implementation must still represent this through a version manifest rather than scattering a literal number through the codebase.

Example:

```java
TargetVersionManifest.minecraft_1_21_11()
```

This manifest should contain:

- Minecraft version
- Resource-pack format
- Known folder conventions
- Supported asset definition formats
- Atlas rules
- Version display name
- Compatibility notes

## 8.5 Important 1.21.11 asset rules

The resolver and validator must account for these facts:

1. Client item definitions live under:

```text
assets/<namespace>/items/<path>.json
```

2. Traditional render models live under:

```text
assets/<namespace>/models/<path>.json
```

3. Item definitions can choose models using conditional, selected, ranged, composite, and other dispatch types.
4. Models are only meaningful when referenced by blockstates, client item definitions, or other models.
5. Item textures and block textures use separate atlas behavior in 1.21.11.
6. Every texture used by one item model must come from a valid single atlas.
7. Every texture used by a block model must be valid for the blocks atlas.
8. Block model and blockstate rotations are more flexible than in older versions.
9. Texture `.mcmeta` files may affect animation, mipmaps, and alpha behavior.
10. Special-case renderers cannot always be represented by ordinary JSON block models.

These rules must shape both the graph and validation systems.

---

# 9. Product Scope by Release

## 9.1 Prototype P0

Purpose: Prove the Fabric project and UI bootstrap.

Included:

- Client initialization
- F8 keybinding
- Open and close the CraftStudio screen
- Basic CraftStudio home screen
- Logging
- No project creation yet

## 9.2 Alpha 0.1

Purpose: Prove project persistence and vanilla asset indexing.

Included:

- New project wizard
- Open project
- Project metadata
- Workspace directory
- Index vanilla block identifiers
- Searchable text list
- Simple asset details
- No extraction yet

## 9.3 Alpha 0.2

Purpose: Prove dependency resolution.

Included:

- Resolve blockstate JSON
- Resolve variants
- Resolve multipart definitions
- Resolve model parent chains
- Resolve texture variables
- Resolve texture PNG and optional `.mcmeta`
- Resolve client item definition and referenced item models
- Build an asset graph
- Display dependency tree

## 9.4 Alpha 0.3

Purpose: Prove project materialization.

Included:

- Add complete bundle
- Add unique-only bundle
- Custom dependency selection
- Copy files into project pack root
- Restore vanilla asset
- Conflict handling
- Project file tree

## 9.5 Alpha 0.4

Purpose: Prove preview.

Included:

- Correct textured block preview
- Variant selector
- Rotation, zoom, pan
- Missing texture visualization
- Refresh after project file change
- Item preview for basic model types

## 9.6 Beta 0.5

Purpose: Produce usable packs.

Included:

- Validation
- Folder export
- ZIP export
- Install into current instance
- Manual resource reload
- Backup before overwrite
- Export report

## 9.7 Release 1.0

Included:

- Polished project dashboard
- Visual asset browser with thumbnails
- Blocks and items
- Dependency graph
- Correct 3D previews for supported standard models
- External editor integration
- Auto-reload option
- Validation center
- Safe export
- User documentation
- Crash-resistant file handling
- Performance tuning
- Accessibility basics

## 9.8 Release 1.1

- GUI asset browser and preview
- Project backups and history
- Compare project asset with vanilla
- Better thumbnail cache
- Recent projects
- Favorites and collections

## 9.9 Release 1.2

- Sound browser
- Font preview
- Color palette generator
- Prism Launcher instance detection
- Paintings and particles
- More special-case model previews

## 9.10 Release 2.0

- Basic model editing
- Version conversion framework
- Plugin SDK
- Community templates
- Advanced item dispatch preview
- Mob and equipment preview
- Multi-version project manifests

---

# 10. Product Principles

## 10.1 Object-first, path-second

Users select “Crafting Table,” not four PNG files and three JSON files.

Paths remain visible in advanced details, but they are not the primary interaction.

## 10.2 Preview and export share one truth

The preview resolver and export resolver must consume the same asset graph.

Never create two independent interpretations of the resource pack.

## 10.3 Vanilla remains recoverable

Any project asset can be restored to the target version’s vanilla source.

## 10.4 Errors must be actionable

Bad:

```text
Failed to load model.
```

Good:

```text
The lit furnace model references
minecraft:block/furnace_front_on,
but textures/block/furnace_front_on.png is missing.

Dependency chain:
minecraft:furnace
→ blockstate lit=true
→ block/furnace_on
→ texture #front
→ minecraft:block/furnace_front_on
```

## 10.5 Safe by default

- No silent overwrite
- No silent deletion
- No writes outside approved roots
- No network requirement
- No background upload
- Backups for destructive operations

## 10.6 Version-specific correctness beats broad compatibility

A strong 1.21.11 tool is more valuable than a fragile “works everywhere” claim.

---

# 11. Information Architecture

The main CraftStudio workspace contains:

```text
Top Bar
├── Project name
├── Save state
├── Reload
├── Validate
├── Export
└── Settings

Left Navigation
├── Dashboard
├── Asset Browser
├── Project Files
├── Preview
├── Validation
└── Export

Main Content
└── Current screen

Right Inspector
├── Selected asset information
├── Dependencies
├── Variants
├── Actions
└── Warnings

Bottom Status Bar
├── Current target version
├── Active task
├── Error/warning counts
└── Auto-reload state
```

The UI may adapt for small resolutions by collapsing the right inspector.

---

# 12. Screen Specifications

## 12.1 Home screen

Displayed when F8 opens CraftStudio with no project selected.

Content:

```text
CraftStudio

[New Project] [Open Project] [Import Existing Pack]

Recent Projects
- Project name
- Target version
- Last opened
- Validation status

Quick Tools
- Browse Vanilla Assets
- Validate Pack
- Open Workspace Folder
```

Requirements:

- Opening must not pause or freeze for a full asset scan.
- Recent projects load from a small registry file.
- Missing project paths are shown as unavailable, not removed silently.

## 12.2 New Project wizard

Fields:

- Project name
- Project slug
- Description
- Author
- Target version, fixed to 1.21.11 for initial release
- Workspace location
- Pack icon, optional
- Create empty project or start from a template

Validation:

- Project name cannot be blank.
- Slug must be path-safe.
- Destination must not overlap another CraftStudio project unless opening it.
- Existing files require explicit confirmation.
- Project folder creation must be atomic where possible.

Generated project:

```text
MyProject/
├── craftstudio.project.json
├── pack/
│   ├── pack.mcmeta
│   ├── pack.png
│   └── assets/
├── .craftstudio/
│   ├── backups/
│   ├── cache/
│   ├── exports/
│   └── logs/
└── README.txt
```

`pack.png` may be omitted if the user does not choose one.

## 12.3 Asset Browser

Primary features:

- Search
- Category filter
- Namespace filter
- Grid/list toggle
- Sort
- “Only assets already in project”
- “Only assets with issues”
- Thumbnail or icon
- Asset name
- Asset type
- Add status

Initial categories:

- Blocks
- Items

Later categories:

- GUI
- Entities
- Sounds
- Fonts
- Models
- Particles
- Paintings
- Environment

Block card actions:

- Open details
- Add complete bundle
- Add unique-only bundle
- Add custom selection
- Preview

## 12.4 Asset Details

Example:

```text
Furnace
minecraft:furnace

Preview: [3D viewport]

States
- facing: north, south, east, west
- lit: true, false

Resolved bundle
- 1 blockstate
- 2 or more models
- parent model dependencies
- 4 textures
- optional texture metadata
- 1 client item definition
- item render models

Shared dependencies
- none or list

Warnings
- none

[Add Complete] [Add Unique Only] [Choose Files]
```

The exact count must come from resolution, not a hardcoded catalog.

## 12.5 Dependency Selection dialog

Sections:

```text
Required
☑ Blockstate
☑ Root models

Referenced models
☑ Parent models

Unique textures
☑ ...

Shared vanilla textures
☐ ...

Item representation
☑ Client item definition
☑ Item models

Metadata
☑ Texture .mcmeta
☑ Atlas additions, when required
```

Rules:

- Required structural files cannot be unchecked in beginner mode.
- Advanced mode may permit incomplete extraction with a warning.
- “Complete” includes every resolved dependency.
- “Unique only” excludes shared leaf dependencies that remain safely available from vanilla.
- A summary explains expected behavior.

## 12.6 Project Files

A friendly tree, not only a raw file system.

```text
Project
├── Blocks
│   └── Furnace
│       ├── Definitions
│       ├── Models
│       └── Textures
├── Items
└── Other Files
```

Advanced toggle:

```text
assets/minecraft/blockstates/furnace.json
assets/minecraft/models/block/furnace.json
assets/minecraft/models/block/furnace_on.json
assets/minecraft/textures/block/furnace_front.png
...
```

Actions:

- Open
- Open in external editor
- Reveal in file manager
- Compare with vanilla
- Restore vanilla
- Remove from project
- Show dependents
- Show dependencies

## 12.7 Preview screen

Controls:

- Asset selector
- Variant selector
- Item/block mode
- Rotate
- Zoom
- Reset camera
- Lighting preset
- Background
- Show face labels
- Show UV overlay
- Show missing dependencies
- Refresh

Initial lighting presets:

- Neutral studio
- Bright
- Dark
- Flat unlit diagnostic

The diagnostic mode should make texture assignment easy to inspect.

## 12.8 Validation Center

Sections:

- Errors
- Warnings
- Information
- Passed checks

Each issue contains:

- Severity
- Summary
- File
- JSON path or texture identifier
- Dependency chain
- Suggested repair
- One-click repair when safe
- Open file action

## 12.9 Export screen

Export targets:

- ZIP
- Folder
- Current instance resourcepacks directory
- Previously used directory

Options:

- Export name
- Include README
- Include project-only metadata, default off
- Run validation first, default on
- Block export on errors, default on
- Permit export with errors, advanced confirmation
- Open destination after export

Project metadata under `.craftstudio/` and `craftstudio.project.json` must not enter the pack unless explicitly requested.

## 12.10 Settings

Initial settings:

- F8 keybinding is managed through Minecraft controls
- Workspace root
- Preferred image editor
- Preferred model editor
- Auto-reload
- Auto-save
- Backup count
- Thumbnail cache limit
- Preview background
- Advanced mode
- Logging level
- Confirm destructive actions

---

# 13. Design System

## 13.1 Visual direction

- Dark neutral canvas
- Crisp panels
- Minecraft-compatible pixel detail without copying the default menu style
- One strong accent color
- Clear hierarchy
- Restrained animation
- Minimal heavy shadows
- High legibility at common GUI scales

## 13.2 Spacing

Use a consistent 4-pixel base spacing system:

```text
4, 8, 12, 16, 24, 32
```

## 13.3 Typography

Use Minecraft’s available font rendering by default.

Requirements:

- Headers must remain readable at GUI scale 2.
- Do not rely only on color for status.
- Long paths should truncate in the middle and show the full path on hover.
- Avoid tiny text below normal Minecraft readability.

## 13.4 Status colors

Use semantic roles rather than hardcoded colors across widgets:

- Success
- Information
- Warning
- Error
- Muted
- Accent

Each status also needs an icon or label.

## 13.5 Interaction states

Every interactive control requires:

- Default
- Hover
- Focus
- Pressed
- Disabled
- Selected

Keyboard focus must be visible.

---

# 14. Core Architecture

## 14.1 Architectural style

Use a modular layered architecture:

```text
Presentation
↓
Application Services
↓
Domain
↓
Infrastructure Adapters
↓
Minecraft, Fabric, File System
```

## 14.2 Modules

### Bootstrap

Responsibilities:

- Client entrypoint
- Service construction
- Keybinding registration
- Lifecycle hooks
- Configuration loading

### UI

Responsibilities:

- Screens
- Widgets
- Navigation
- View models
- User notifications
- Task progress

### Project

Responsibilities:

- Project creation
- Project loading
- Metadata
- Save state
- Pack root
- Recent projects

### Asset Catalog

Responsibilities:

- Discover available block and item identifiers
- Search index
- Categories
- Thumbnail requests
- Asset metadata

### Asset Graph

Responsibilities:

- Nodes
- Edges
- Resolution results
- Dependency chains
- Shared dependency analysis
- Cycle detection

### Resolver

Responsibilities:

- Blockstate parsing
- Variant expansion
- Multipart expansion
- Model parent resolution
- Texture variable resolution
- Client item definition parsing
- Item model dispatch traversal
- Texture and metadata lookup
- Atlas classification

### Preview

Responsibilities:

- Preview scene
- Model rendering adapter
- Variant selection
- Texture source selection
- Diagnostic overlays
- Camera controls

### Validation

Responsibilities:

- Structural validation
- JSON validation
- Dependency validation
- Atlas validation
- Metadata validation
- Export readiness

### Export

Responsibilities:

- Build staging directory
- Copy project pack
- Validate output
- Create ZIP
- Atomic publish
- Export report

### Live Reload

Responsibilities:

- File watching
- Debouncing
- Change classification
- Cache invalidation
- Resource reload requests

### Infrastructure

Responsibilities:

- Vanilla asset source
- Effective resource source
- Project asset source
- File system
- JSON
- ZIP
- Process launching
- Logging
- Platform paths

---

# 15. Recommended Package Structure

```text
dev.arcn.craftstudio
├── CraftStudio.java
├── client
│   ├── CraftStudioClient.java
│   ├── bootstrap
│   ├── input
│   ├── integration
│   └── lifecycle
├── config
│   ├── CraftStudioConfig.java
│   └── ConfigRepository.java
├── project
│   ├── domain
│   ├── application
│   └── infrastructure
├── catalog
│   ├── domain
│   ├── application
│   └── infrastructure
├── graph
│   ├── domain
│   ├── resolver
│   └── diagnostics
├── preview
│   ├── domain
│   ├── application
│   ├── minecraft
│   └── ui
├── validation
│   ├── rules
│   ├── domain
│   └── application
├── export
│   ├── domain
│   ├── application
│   └── infrastructure
├── reload
├── ui
│   ├── screen
│   ├── widget
│   ├── navigation
│   ├── theme
│   └── viewmodel
├── platform
│   ├── filesystem
│   ├── process
│   └── paths
└── util
```

Do not create dozens of empty abstraction files before they are needed. Grow this structure by milestone.

---

# 16. Minecraft Asset Graph

## 16.1 Purpose

The asset graph is the central domain model.

It represents:

- What the user selected
- What files are required
- Why each file is required
- Which files are shared
- Which files are missing
- Which files are overridden by the project
- Which files are used for preview
- Which files are copied during export

## 16.2 Node types

Initial node types:

```text
BLOCK
ITEM
BLOCKSTATE_FILE
CLIENT_ITEM_FILE
MODEL_FILE
TEXTURE_FILE
TEXTURE_METADATA_FILE
ATLAS_FILE
BUILTIN_MODEL
SPECIAL_RENDERER
UNKNOWN_RESOURCE
```

Future node types:

```text
SOUND_EVENT
SOUND_FILE
FONT_PROVIDER
FONT_TEXTURE
PARTICLE_DEFINITION
GUI_SPRITE
ENTITY_TEXTURE
EQUIPMENT_ASSET
PAINTING_VARIANT
```

## 16.3 Edge types

```text
HAS_BLOCKSTATE
HAS_CLIENT_ITEM
SELECTS_MODEL
USES_MODEL
INHERITS_MODEL
USES_TEXTURE_VARIABLE
RESOLVES_TEXTURE
USES_METADATA
REQUIRES_ATLAS
SHARED_BY
HAS_VARIANT
HAS_MULTIPART_CASE
USES_SPECIAL_RENDERER
```

## 16.4 Node identity

Every node must have a stable identity composed of:

- Node type
- Namespace
- Logical path or registry identifier
- Target version

File-backed nodes additionally have:

- Relative pack path
- Source layer
- Content hash, optional

## 16.5 Source layers

A resource may come from:

```text
VANILLA_BASE
ACTIVE_PACK_STACK
PROJECT
GENERATED
MISSING
```

For normal project creation:

- Extraction source defaults to `VANILLA_BASE`.
- Preview source uses `PROJECT` first, then falls back to `VANILLA_BASE`.
- An optional later mode may inspect the active pack stack.

## 16.6 Graph resolution result

```java
public record AssetResolutionResult(
    AssetKey root,
    AssetGraph graph,
    List<ResolutionIssue> issues,
    ResolutionStats stats
) {}
```

## 16.7 Dependency classifications

Each dependency is classified as:

- Required root
- Required transitive
- Optional
- Shared vanilla
- Generated
- Missing
- Unsupported special case

## 16.8 Shared dependency rule

A dependency is considered shared when it is used by more than one logical root asset in the vanilla catalog or is a generic built-in resource.

Examples:

- `minecraft:block/cube_all`
- `minecraft:item/generated`
- A commonly reused plank texture
- Shared particle textures

Shared does not mean safe to omit in every context. The resolver must calculate whether vanilla fallback remains valid for the project’s chosen override.

---

# 17. Block Resolution

## 17.1 Resolution pipeline

```text
Block registry identifier
→ Locate blockstate definition
→ Parse variants and multipart cases
→ Collect referenced models
→ Apply blockstate rotations and UV-lock metadata
→ Resolve each model parent chain
→ Merge inherited model properties
→ Resolve texture variables
→ Locate texture PNGs
→ Locate optional texture .mcmeta
→ Classify atlas
→ Resolve item representation
→ Build graph
```

## 17.2 Blockstate variants

Support:

- Empty/default variant keys
- Property combinations
- Multiple weighted models
- X/Y/Z rotations supported by 1.21.11 format
- UV lock
- Unknown fields preserved diagnostically

The first UI does not need to simulate random weighted selection. It must list every model possibility.

## 17.3 Multipart

Support:

- AND conditions
- OR conditions
- Multiple apply entries
- Weighted apply entries
- Unconditional parts

The asset details screen should list logical multipart branches.

## 17.4 Model parent resolution

Rules:

1. Load the referenced model.
2. Follow `parent` recursively.
3. Detect cycles.
4. Merge inherited properties according to Minecraft behavior.
5. Resolve texture variables after the effective texture table is known.
6. Preserve the chain for diagnostics.
7. Treat built-in model parents as special nodes when no ordinary JSON file exists.

## 17.5 Texture variables

Example:

```json
{
  "textures": {
    "top": "minecraft:block/crafting_table_top",
    "side": "minecraft:block/crafting_table_side",
    "front": "minecraft:block/crafting_table_front",
    "particle": "#front"
  }
}
```

A face may use:

```json
"texture": "#side"
```

Resolution must:

- Support direct resource identifiers
- Support one variable referencing another
- Detect undefined variables
- Detect cycles
- Record the originating model and variable chain

## 17.6 Model elements

Preview support requires:

- `from`
- `to`
- Per-element rotation
- Multi-axis rotation supported by 1.21.11
- `rescale`
- `shade`
- `light_emission`, when applicable
- Per-face UV
- Per-face texture
- Face rotation
- Cull face metadata
- Tint index
- Ambient occlusion
- Display transforms where relevant

## 17.7 Block item representation

A placed block and its inventory item may not use identical files.

The resolver must separately include:

- Client item definition
- Referenced item model graph
- Shared block model references
- Item-specific textures
- Item atlas requirements

The UI should display “Block appearance” and “Inventory appearance” as separate preview modes.

---

# 18. Item Resolution

## 18.1 Resolution pipeline

```text
Item registry identifier
→ Locate client item definition
→ Traverse item model definition
→ Discover referenced render models
→ Resolve render model parent chains
→ Resolve textures
→ Evaluate atlas constraints
→ Collect all possible branches
→ Build graph
```

## 18.2 Required 1.21.11 distinction

Do not confuse:

```text
assets/<namespace>/items/<path>.json
```

with:

```text
assets/<namespace>/models/<path>.json
```

The first is the client item definition. The second is a render model.

An item may require both.

## 18.3 Item definition traversal

The resolver should recognize standard definition categories such as:

- Plain model
- Composite
- Condition
- Select
- Range dispatch
- Empty
- Special
- Bundle-selected-item or nested forms
- Other registered vanilla item model types

The exact type registry and field names must be verified against 1.21.11 assets and mappings during implementation.

For unsupported branches:

- Preserve the raw JSON
- Add an `UNSUPPORTED_PREVIEW` warning
- Continue collecting directly referenced resources where safely possible
- Do not crash indexing

## 18.4 Preview contexts

Initial item preview contexts:

- GUI
- Ground
- Fixed/item frame
- First-person right hand
- Third-person right hand

Advanced dispatch inputs can be simulated later.

## 18.5 Item branch presentation

Example:

```text
Bow

Branches
├── Idle
├── Pulling stage 0
├── Pulling stage 1
└── Pulling stage 2
```

The user can include all branches by default.

---

# 19. Atlas Awareness

## 19.1 Why atlases matter

Minecraft does not render every PNG by directly opening it at model draw time. Textures are collected into atlases.

CraftStudio must validate that model textures are legal for their intended atlas.

## 19.2 1.21.11 rules

At minimum:

- Block model textures must belong to the blocks atlas.
- All textures used by a specific item model must come from one valid atlas.
- Item textures may belong to the items atlas.
- Atlas definitions may add or remove sprite sources.
- Duplicate sprite identifiers can create warnings.
- Special atlases exist for beds, chests, signs, banners, paintings, particles, and other categories.

## 19.3 MVP behavior

- Classify standard vanilla block and item textures.
- Warn when an item model mixes incompatible atlas sources.
- Include custom atlas JSON only when the project actually needs it.
- Do not generate atlas files for ordinary direct vanilla overrides.
- Preserve project atlas files during import and export.
- Defer a full atlas editor to a later release.

---

# 20. Special-Case Assets

Some Minecraft visuals do not use normal block model rendering.

Examples may include:

- Chests
- Beds
- Signs
- Banners
- Shulker boxes
- Fluids
- Skulls
- Conduits
- End portals
- Certain block entities
- Dynamic entity-like renderers

CraftStudio must never pretend these are ordinary models.

MVP handling:

```text
Asset can be extracted: Yes
Standard 3D preview: Unsupported or limited
Reason: Special renderer
Relevant textures: Listed
Relevant model/data files: Listed
```

The asset card should show a small “special renderer” badge.

Later releases can implement dedicated preview adapters.

---

# 21. Asset Catalog

## 21.1 Catalog sources

Initial catalog roots:

- Block registry
- Item registry

For each root:

- Registry identifier
- Display name
- Namespace
- Translation key
- Basic category
- Search terms
- Graph resolution status
- Preview support status

## 21.2 Indexing strategy

Do not fully resolve every graph at startup.

Use two stages:

### Lightweight index

Build quickly:

- IDs
- Names
- Types
- Search tokens
- Project membership

### Lazy deep resolution

Run when:

- Asset details are opened
- Asset is added
- Preview is requested
- Validation needs the asset

Cache deep results by:

- Target version
- Root asset
- Source hash
- Project change revision

## 21.3 Search

Search fields:

- Display name
- Registry ID
- Path
- Tags/categories
- Dependency names, later

Search behavior:

- Case-insensitive
- Tokenized
- Prefix matches ranked strongly
- Exact ID match ranked first
- No expensive graph scan for each keystroke

## 21.4 Thumbnail generation

Initial approach:

- Use Minecraft’s existing item/block rendering where safe.
- Generate thumbnails lazily.
- Cache by asset ID, variant, project revision, and UI scale.
- Show placeholders while loading.
- Bound memory and disk cache size.

---

# 22. Asset Source Abstraction

## 22.1 Interface

Conceptual interface:

```java
public interface AssetSource {
    Optional<ResourceData> read(ResourcePath path);
    Stream<ResourcePath> list(String namespace, String prefix);
    SourceLayer layer();
    String revision();
}
```

Implementations:

- `VanillaAssetSource`
- `ProjectAssetSource`
- `LayeredPreviewAssetSource`
- `ImportedPackAssetSource`, later

## 22.2 Vanilla source

The vanilla source must return Minecraft 1.21.11 base assets, not silently return a third-party pack override.

Implementation may use the game’s built-in resource pack interfaces or a verified game asset provider.

The chosen approach must be tested with another resource pack enabled to confirm that extraction still produces vanilla files.

## 22.3 Layered preview source

Order:

```text
Project pack
→ Vanilla base
```

Later optional order:

```text
Project pack
→ Selected imported dependency packs
→ Vanilla base
```

## 22.4 Resource safety

All resource paths must be normalized.

Reject:

- Absolute paths
- `..` traversal
- Null bytes
- Invalid namespace identifiers
- Invalid relative pack paths

---

# 23. Project Model

## 23.1 Project metadata

`craftstudio.project.json` example:

```json
{
  "schema_version": 1,
  "project_id": "018f-example-uuid",
  "name": "My Furnace Pack",
  "slug": "my-furnace-pack",
  "description": "A custom furnace texture pack.",
  "author": "Naitik",
  "target": {
    "minecraft": "1.21.11",
    "resource_pack_format": 75
  },
  "pack_root": "pack",
  "created_at": "2026-07-24T00:00:00Z",
  "updated_at": "2026-07-24T00:00:00Z",
  "selected_roots": [
    {
      "type": "block",
      "id": "minecraft:furnace",
      "selection_mode": "complete"
    }
  ],
  "settings": {
    "auto_reload": false,
    "advanced_mode": false
  }
}
```

Timestamps should be real runtime values, not the example above.

## 23.2 Pack metadata

Generated `pack/pack.mcmeta` must be valid for 1.21.11.

The version manifest owns the format value.

The description may include styled text later, but MVP can use a simple string or a safe text component supported by the target version.

## 23.3 Derived state

Do not store a full graph as the only truth.

The graph can be cached, but it must be rebuildable from:

- Selected roots
- Project files
- Target manifest
- Vanilla source

## 23.4 Project dirty state

A project is dirty when:

- Metadata changed
- Selection changed
- Generated file changed
- External file modification was detected
- Validation state is stale

Auto-save metadata after a debounce. Never auto-overwrite conflicting external edits without detection.

---

# 24. Project File Operations

## 24.1 Add bundle

Algorithm:

1. Resolve asset graph.
2. Apply selection mode.
3. Build copy plan.
4. Detect destination conflicts.
5. Show conflict summary if needed.
6. Create backup for overwritten CraftStudio-managed files.
7. Copy to a staging location.
8. Validate copied files.
9. Atomically publish files where possible.
10. Update selected roots.
11. Invalidate caches.
12. Refresh project tree and preview.

## 24.2 Conflict modes

- Keep existing
- Replace with vanilla
- Replace selected files only
- Compare
- Cancel

Never default to replacing edited project files.

## 24.3 Remove asset

Removing a root asset must calculate:

- Files exclusively owned by that root
- Files shared with other selected roots
- Files manually edited or added
- Dependents that would become broken

Default action:

- Remove root association
- Offer to remove only unshared generated files
- Keep manually modified files unless explicitly selected

## 24.4 Restore vanilla

Before restore:

- Show file
- Show dependents
- Create backup
- Confirm

Restore must use the target version’s vanilla source.

---

# 25. Preview Engine

## 25.1 Trust requirement

The preview is a correctness tool, not decoration.

It must render from the same effective resolved resources used by validation and export.

## 25.2 Initial strategy

Prefer reusing Minecraft’s model baking and rendering pipeline where it can consume project-backed resources safely.

Where direct reuse is difficult, build a controlled preview adapter that:

- Parses the effective model
- Applies resolved textures
- Produces renderable geometry
- Uses Minecraft texture and buffer systems
- Matches model transforms and UV behavior

Do not create a completely unrelated OBJ-style renderer that ignores Minecraft semantics.

## 25.3 Preview scene

Contains:

- Camera
- Model transform
- Neutral light
- Optional world-style light
- Background plane or gradient
- Render target
- Diagnostic overlay

## 25.4 Camera controls

- Left drag: rotate
- Scroll: zoom
- Middle drag or modified drag: pan
- Double click: reset
- Keyboard arrows: rotate when focused
- Reset button

## 25.5 Texture resolution

For every face:

1. Read face texture key.
2. Resolve model texture variables.
3. Resolve inherited variables.
4. Resolve resource identifier.
5. Load project override if present.
6. Otherwise load vanilla.
7. Apply UV coordinates.
8. Apply face rotation.
9. Apply tint if supported.
10. Display missing texture only when unresolved.

## 25.6 Variant selector

For blocks:

- Property dropdowns
- Direct variant list
- Multipart branch visualization
- Lit/unlit presets where detected
- Facing controls

The selected state should explain which blockstate branch and model are active.

## 25.7 Live refresh

When a watched file changes:

- Debounce rapid save bursts
- Invalidate only affected nodes
- Re-resolve dependents
- Refresh preview
- Update validation
- Show a small success or error toast

A full resource reload can remain available as a fallback.

## 25.8 Preview limitations disclosure

When unsupported:

```text
Preview unavailable for this asset because it uses a special renderer.
Extraction and export are still supported.
```

Never display an incorrect generic cube as if it were accurate.

---

# 26. Live Reload System

## 26.1 Modes

- Off
- Manual
- Auto-reload project preview
- Auto-reload Minecraft resources, optional advanced mode

## 26.2 File watching

Watch only the active project’s pack directory.

Requirements:

- Recursive
- Debounced
- Handles editor save patterns that replace files
- Handles create, modify, move, and delete
- Stops when project closes
- Recovers when folders are recreated

## 26.3 Reload classification

```text
PNG or .mcmeta
→ texture cache invalidation

Blockstate/model/item JSON
→ graph and model invalidation

pack.mcmeta
→ metadata validation

Atlas JSON
→ atlas validation and broad render invalidation

Unknown asset
→ project tree refresh and targeted validation
```

## 26.4 Threading

File watching and parsing must not block the render thread.

Minecraft resource reload requests must be scheduled through the appropriate client execution context.

---

# 27. Validation Engine

## 27.1 Severity levels

- Error
- Warning
- Information
- Passed

## 27.2 Structural rules

- `pack.mcmeta` exists
- Metadata parses
- Correct target pack format
- Pack root is correct
- Asset paths are valid
- Namespace names are valid
- File extensions are expected

## 27.3 JSON rules

- Valid JSON syntax
- Required fields
- Valid identifiers
- Correct field types
- Unknown fields reported only when meaningful
- JSON path included in issue

## 27.4 Graph rules

- Missing blockstate
- Missing client item definition
- Missing model
- Missing parent
- Parent cycle
- Missing texture variable
- Texture variable cycle
- Missing texture
- Missing texture metadata target
- Broken item branch
- Unsupported special model type
- Dead/unreachable project model
- Orphaned project texture, warning only

## 27.5 Atlas rules

- Block model texture invalid for block atlas
- Item model mixes incompatible atlases
- Duplicate sprite identifiers
- Invalid atlas source
- Missing custom atlas dependency

## 27.6 Image rules

- PNG can be decoded
- Width and height greater than zero
- Animation metadata compatible with frame dimensions
- Frame indexes valid
- Optional warning for unusually large textures
- Transparency issues only when deterministically invalid

Do not enforce “power of two” as a universal requirement if Minecraft supports the file.

## 27.7 Export rules

- No project metadata leaks into pack
- ZIP root contains `pack.mcmeta`
- No extra enclosing folder
- Every planned file exists
- Output can be reopened and listed
- Export destination is writable
- Existing target handling is explicit

## 27.8 One-click repairs

Safe repairs:

- Restore missing vanilla dependency
- Regenerate `pack.mcmeta`
- Create missing folder
- Normalize generated metadata
- Remove empty generated cache file

Unsafe repairs require confirmation:

- Replace edited texture
- Rewrite custom JSON
- Delete orphaned file
- Change atlas configuration

---

# 28. Export Pipeline

## 28.1 Pipeline

```text
Save project metadata
→ Run validation
→ Build export plan
→ Create temporary staging directory
→ Copy pack contents
→ Exclude CraftStudio project internals
→ Verify pack root
→ Re-run critical validation on staging
→ Create folder or ZIP
→ Verify output
→ Atomically replace final output
→ Write export report
→ Notify user
```

## 28.2 ZIP rules

The ZIP root must contain:

```text
pack.mcmeta
pack.png, optional
assets/
```

Incorrect:

```text
MyPack/MyPack/pack.mcmeta
```

Correct:

```text
MyPack.zip
└── pack.mcmeta
```

## 28.3 Existing output

Options:

- Cancel
- Replace with backup
- Choose another name

No silent replacement.

## 28.4 Export report

Contains:

- Target version
- Timestamp
- Number of files
- Root assets
- Validation summary
- Output path
- Content hash, optional
- Warnings accepted by user

---

# 29. External Editor Integration

## 29.1 Supported actions

- Open in system default application
- Open with configured image editor
- Open with configured model editor
- Reveal file in file manager
- Open project folder

## 29.2 Process launching

Use argument-separated process APIs.

Never construct a shell command by concatenating paths.

Handle:

- Spaces
- Unicode paths
- Missing application
- Permission errors
- Application exit not required

## 29.3 Editor detection

MVP:

- User chooses executable or command through settings
- System default always available where supported

Later:

- Detect Krita
- Detect GIMP
- Detect Aseprite
- Detect Blockbench
- Detect Pinta
- Detect Paint.NET on Windows

---

# 30. Performance Requirements

## 30.1 UX targets

On the user’s target class of laptop:

- F8 screen appears within 300 ms after first initialization.
- Initial home screen does not wait for full asset resolution.
- Lightweight block/item catalog becomes usable within 2 seconds.
- Search updates within 100 ms for normal input.
- Opening a previously cached simple asset detail takes under 250 ms.
- Uncached dependency resolution for a normal block aims for under 1 second.
- Preview interaction aims for 60 FPS.
- File saves must not freeze the render thread.
- Export progress remains responsive.

These are goals, not reasons to sacrifice correctness.

## 30.2 Memory

- Use bounded thumbnail caches.
- Do not retain decoded images for every asset.
- Store compact graph nodes.
- Release preview resources when project closes.
- Avoid full copies of large resource files in memory when streaming is possible.

## 30.3 Background tasks

Use a bounded executor for:

- Catalog indexing
- JSON parsing
- Hashing
- File copies
- ZIP creation
- Validation
- Thumbnail preparation where safe

All UI and render state mutations return to the client thread.

## 30.4 Cancellation

Long tasks must support cancellation:

- Indexing
- Validation
- Export
- Import
- Large bundle copy

Cancellation removes only task-owned temporary data.

---

# 31. Error Handling

## 31.1 Error categories

- User input
- File system
- Parsing
- Resolution
- Preview
- Validation
- Export
- Internal
- Unsupported asset

## 31.2 User-facing behavior

- Show concise summary
- Preserve technical details in expandable section
- Offer retry
- Offer open logs
- Keep project recoverable
- Avoid crashing the whole client for a malformed project file

## 31.3 Logging

Use structured context:

```text
project_id
asset_id
resource_path
task_id
source_layer
operation
```

Never log full file contents by default.

## 31.4 Recovery

On startup:

- Detect incomplete temporary operations
- Remove safe abandoned temp files
- Preserve backups
- Warn about projects that were not cleanly saved
- Never auto-delete ambiguous user data

---

# 32. Persistence and Backups

## 32.1 Configuration

Store global configuration in the normal Fabric config area.

## 32.2 Recent projects

Store:

- Path
- Project ID
- Name
- Last opened
- Target version
- Last known status

## 32.3 Backups

Create backups before:

- Restore vanilla
- Replace conflicting files
- Destructive repair
- Overwriting export
- Removing modified files

Backup policy:

- Configurable retention count
- Oldest first removal
- Never remove a backup currently referenced by an undo operation
- Display backup storage usage

## 32.4 Atomic metadata writes

Write metadata to a temporary file, flush, then move into place.

Keep a last-known-good copy when practical.

---

# 33. Security and Privacy

CraftStudio must:

- Work offline
- Make no network requests in MVP
- Collect no telemetry
- Upload no files
- Read only game assets, CraftStudio configuration, and user-selected project paths
- Write only to CraftStudio paths, project paths, and explicit export destinations
- Sanitize ZIP entry paths
- Prevent path traversal
- Avoid executing project content
- Treat JSON and image files as untrusted input
- Bound image dimensions before full decode where possible
- Bound archive extraction if pack import is added

---

# 34. Accessibility

Initial requirements:

- Full keyboard navigation for major screens
- Visible focus
- Text labels for icons
- Do not use color alone
- Scalable layout for Minecraft GUI scale
- Tooltips for technical terms
- Reduced animation setting
- High-contrast diagnostic mode
- Screen narration labels where supported by Minecraft UI APIs

---

# 35. Compatibility

## 35.1 Mod compatibility

CraftStudio should avoid:

- Replacing broad rendering systems
- Global resource-manager mixins when an API or narrow adapter works
- Modifying unrelated screens
- Hard dependency on Sodium or Iris
- Assuming the vanilla launcher directory layout

## 35.2 Other resource packs

Tests must cover:

- No other pack enabled
- Another pack enabled above vanilla
- Project preview still uses project-over-vanilla behavior
- Vanilla extraction remains vanilla

## 35.3 Operating systems

Initial support goal:

- Linux
- Windows
- macOS

Primary development environment may be Fedora KDE, but paths and process launching must remain cross-platform.

---

# 36. Testing Strategy

## 36.1 Unit tests

Pure Java tests for:

- Resource path normalization
- Identifier parsing
- Project metadata serialization
- Blockstate parsing
- Parent-chain resolution
- Texture variable resolution
- Cycle detection
- Shared dependency classification
- Export path layout
- ZIP entry safety
- Validation rules

## 36.2 Golden fixture tests

Create small test resource packs under test resources.

Fixtures:

- Simple cube block
- Multi-texture crafting-table-style block
- Lit/unlit furnace-style variants
- Multipart fence-style model
- Parent model chain
- Missing texture
- Missing parent
- Texture variable cycle
- Item client definition plus render model
- Composite item
- Conditional item
- Mixed-atlas invalid item
- Animated texture with `.mcmeta`
- Special-renderer marker

Expected graph snapshots should be reviewed and version controlled.

## 36.3 Integration tests

- Create project
- Add bundle
- Edit texture
- Validate
- Export ZIP
- Reopen ZIP
- Verify root
- Load as resource pack in a development client

## 36.4 Manual test matrix

Blocks:

- Stone
- Crafting table
- Furnace
- Oak door
- Oak stairs
- Oak fence
- Redstone wire
- Crop
- Chest
- Bed
- Water or special fluid case

Items:

- Diamond
- Diamond sword
- Bow
- Compass
- Shield
- Potion
- Spawn egg
- Spear from 1.21.11
- Block item

## 36.5 Regression rule

Every bug involving a parseable resource structure must gain a fixture test.

---

# 37. Acceptance Criteria

## 37.1 P0 bootstrap

- Game launches in Fabric 1.21.11.
- CraftStudio is listed as a client mod.
- F8 opens the screen.
- Escape closes it.
- Dedicated server classes are not referenced by client entrypoint mistakes.
- Build succeeds.

## 37.2 Project creation

- Valid project structure is generated.
- Metadata can be reopened.
- Invalid paths are rejected.
- `pack.mcmeta` is generated for target version.
- No project internals enter the pack root.

## 37.3 Asset catalog

- All vanilla registered blocks and items appear.
- Search finds by name and ID.
- UI remains responsive.
- Catalog does not fully resolve every asset at startup.

## 37.4 Block resolver

For crafting table and furnace examples:

- Every blockstate-referenced model is found.
- Parent models are followed.
- Every texture variable resolves.
- All PNG dependencies are listed.
- Lit and unlit furnace branches are shown.
- Item representation is included.
- Missing files produce issues instead of crashes.

## 37.5 Bundle extraction

- Complete mode copies all dependencies.
- Unique-only mode excludes safe shared vanilla leaves.
- Custom mode follows checkboxes.
- Existing edits are not overwritten silently.
- Project graph refreshes after copy.

## 37.6 Preview

- Crafting table faces show correct top, front, side, and bottom mappings.
- Furnace preview switches between lit and unlit.
- Facing rotates the correct front.
- Project texture override appears after refresh.
- Missing texture affects only unresolved faces where possible.
- Unsupported special assets show a truthful limitation message.

## 37.7 Validation and export

- Missing texture blocks export by default.
- User can locate the broken dependency.
- Valid project exports to folder and ZIP.
- ZIP root is correct.
- Exported pack loads in Minecraft 1.21.11.
- Export does not include `.craftstudio` or project metadata.

---

# 38. Development Milestones

## Milestone 0: Generate and verify template

Tasks:

1. Generate Fabric 1.21.11 template.
2. Use Java.
3. Enable split client/common sources.
4. Confirm Java 21.
5. Import Gradle project.
6. Run client.
7. Run build.
8. Commit untouched working baseline.

Deliverable:

```text
Minecraft launches with CraftStudio template installed.
```

Do not begin feature development until both `runClient` and `build` succeed.

## Milestone 1: Client bootstrap and F8 screen

Tasks:

- Add client initializer.
- Register F8 keybinding.
- Open `CraftStudioHomeScreen`.
- Add title, New Project, Open Project, and Close buttons.
- Add logger.
- Add minimal theme tokens.

Deliverable:

```text
F8 reliably opens CraftStudio.
```

## Milestone 2: Project foundation

Tasks:

- Define project metadata record.
- Create workspace paths.
- Implement new project wizard.
- Generate pack root and metadata.
- Reopen project.
- Add recent project registry.
- Add atomic JSON writes.

Deliverable:

```text
A valid empty 1.21.11 resource-pack project can be created and reopened.
```

## Milestone 3: Lightweight vanilla catalog

Tasks:

- Enumerate block and item registries.
- Resolve display names.
- Build search index in background.
- Display list.
- Add asset details placeholder.

Deliverable:

```text
Users can browse and search vanilla blocks and items.
```

## Milestone 4: Resource source adapters

Tasks:

- Define `AssetSource`.
- Implement project source.
- Implement vanilla base source.
- Test with another resource pack enabled.
- Add path safety.
- Add resource revision identifiers.

Deliverable:

```text
CraftStudio can read a known vanilla blockstate, model, and texture without receiving an active pack override.
```

## Milestone 5: Block dependency resolver

Implementation order:

1. Single variant
2. Multiple variants
3. Parent models
4. Texture variables
5. PNG and `.mcmeta`
6. Multipart
7. Weighted alternatives
8. Block item representation
9. Atlas classification
10. Special renderer detection

Start fixtures:

- Stone
- Crafting table
- Furnace

Deliverable:

```text
Dependency tree is correct for crafting table and furnace.
```

## Milestone 6: Item dependency resolver

Tasks:

- Parse client item definition.
- Traverse plain model.
- Add composite.
- Add condition/select/range branches.
- Resolve model and textures.
- Report unsupported types without crashing.
- Add atlas validation.

Deliverable:

```text
Basic items and multi-state items expose complete branch dependencies.
```

## Milestone 7: Add-to-project workflow

Tasks:

- Complete mode
- Unique-only mode
- Custom mode
- Copy plan
- Conflict detection
- Backups
- Restore vanilla
- Remove root safely

Deliverable:

```text
Selecting furnace creates a working resource-pack asset bundle.
```

## Milestone 8: Preview

Tasks:

- Preview viewport
- Camera
- Simple baked block
- Correct texture assignment
- Variant controls
- Lit/unlit furnace
- Block item mode
- Missing-texture diagnostics
- Cache invalidation

Deliverable:

```text
Crafting table and furnace render correctly from the layered project source.
```

## Milestone 9: Live reload and external editor

Tasks:

- File watcher
- Debounce
- Open in system editor
- Preferred editor setting
- Targeted invalidation
- Manual reload fallback

Deliverable:

```text
Saving a changed PNG refreshes the preview without restarting the game.
```

## Milestone 10: Validation and export

Tasks:

- Validation framework
- Critical rules
- Validation center UI
- ZIP staging
- Folder export
- Current instance install
- Export report
- Safe overwrite behavior

Deliverable:

```text
A valid exported pack loads in 1.21.11 and an invalid pack explains why it cannot export.
```

## Milestone 11: Polish and release

Tasks:

- Thumbnails
- UI refinement
- Keyboard navigation
- Narration labels
- Performance profiling
- Crash recovery
- Documentation
- Mod icon
- Modrinth metadata
- License
- Changelog

Deliverable:

```text
CraftStudio 1.0.0 for Fabric 1.21.11.
```

---

# 39. AI Work Order Format

Each coding-agent prompt should use this structure:

```text
Project: CraftStudio
Target: Fabric 1.21.11, Java 21
Current milestone: <number and name>
Existing state: <what currently works>
Task: <one focused outcome>
Constraints:
- Client-side only
- Do not change target version
- Do not introduce unrequested dependencies
- Preserve split source sets
- Keep Minecraft API access behind adapters
- Add tests for pure logic
Required verification:
- ./gradlew build
- Relevant tests
- Manual runClient behavior
Output:
- Summary
- Files changed
- Verification results
- Remaining risks
```

Do not give an AI agent the entire 1.0 scope as one coding request.

---

# 40. Coding Standards

## 40.1 Java

- Java 21
- Records for immutable data where useful
- Sealed interfaces for finite domain variants where useful
- Avoid reflection unless absolutely required
- Avoid global mutable state
- Use `Optional` only for return values where absence is meaningful
- Validate data at boundaries
- Keep UI state separate from persisted domain state
- Use descriptive names

## 40.2 Concurrency

- Never block the render thread with disk I/O.
- Return UI changes to the client thread.
- Bound executors.
- Make tasks cancellable.
- Use immutable results between threads.
- Do not access Minecraft render objects from arbitrary worker threads.

## 40.3 JSON

- Preserve unknown fields when importing and rewriting user-authored JSON where possible.
- Do not reformat or rewrite user JSON merely by opening it.
- Generated JSON should be deterministic.
- Error messages include file and JSON path.

## 40.4 File system

- Use `Path`.
- Normalize and validate.
- Use staging for complex writes.
- Do not follow symbolic links outside allowed roots during recursive operations unless explicitly designed and confirmed.
- Use UTF-8.
- Stream large files.

## 40.5 Minecraft integration

- Minimize mixins.
- Prefer Fabric APIs and public game interfaces.
- Every mixin needs a written reason.
- Use access wideners only when a narrow verified need exists.
- Encapsulate mapping-sensitive calls.

---

# 41. Suggested Core Domain Types

```java
public record AssetKey(
    AssetKind kind,
    String namespace,
    String path
) {}

public enum AssetKind {
    BLOCK,
    ITEM
}

public record ResourcePath(
    String namespace,
    String relativePath
) {}

public sealed interface AssetNode permits
    LogicalAssetNode,
    FileAssetNode,
    BuiltinAssetNode,
    MissingAssetNode {}

public record DependencyEdge(
    AssetNodeId from,
    AssetNodeId to,
    DependencyType type,
    String detail
) {}

public record ResolutionIssue(
    Severity severity,
    String code,
    String message,
    Optional<ResourcePath> resource,
    List<AssetNodeId> dependencyChain
) {}

public enum SelectionMode {
    COMPLETE,
    UNIQUE_ONLY,
    CUSTOM
}
```

These are examples. Keep the domain expressive, but do not over-engineer before milestone needs.

---

# 42. Suggested Application Services

```text
ProjectService
- createProject
- openProject
- saveProject
- closeProject

CatalogService
- buildLightweightIndex
- search
- getDetails

AssetResolutionService
- resolveBlock
- resolveItem
- invalidate

BundleService
- createCopyPlan
- addToProject
- removeFromProject
- restoreVanilla

PreviewService
- openPreview
- selectVariant
- refresh
- invalidate

ValidationService
- validateProject
- validateAsset
- applySafeRepair

ExportService
- planExport
- exportFolder
- exportZip
- installCurrentInstance
```

---

# 43. Risks and Mitigations

## 43.1 Mapping-sensitive Minecraft APIs

Risk:

- Internal names and signatures may differ from current documentation.

Mitigation:

- Use the exact 1.21.11 mappings from the generated project.
- Encapsulate calls.
- Avoid copying 26.x code blindly.
- Add compile checkpoints after each integration.

## 43.2 Vanilla base assets versus active pack stack

Risk:

- Resource manager may return overridden content.

Mitigation:

- Implement and test a vanilla-specific source.
- Run a fixture test with a third-party pack enabled.
- Show source layer in diagnostics.

## 43.3 Special renderers

Risk:

- Generic model preview cannot represent them.

Mitigation:

- Detect and label.
- Support extraction first.
- Add dedicated adapters later.
- Never fake correctness.

## 43.4 New item model system complexity

Risk:

- Conditional and dynamic item definitions create many branches.

Mitigation:

- Traverse all static references.
- Add branch preview incrementally.
- Preserve unsupported raw nodes.
- Start with plain model before complex types.

## 43.5 UI performance

Risk:

- Thousands of thumbnails freeze the game.

Mitigation:

- Lazy rendering
- Virtualized lists
- Bounded caches
- Background indexing
- Placeholders

## 43.6 User file loss

Risk:

- Restore, removal, or conflict resolution overwrites edits.

Mitigation:

- Backups
- Explicit conflict dialog
- Atomic writes
- Ownership metadata
- Never silent overwrite

## 43.7 Scope explosion

Risk:

- GUI editor, mob viewer, model editor, and version conversion delay the core.

Mitigation:

- Enforce milestone scope.
- Finish blocks, items, preview, validation, and export first.
- Place future features behind roadmap labels.

---

# 44. Open Decisions Resolved for Initial Development

| Decision | Initial choice |
|---|---|
| Product name | CraftStudio |
| Mod ID | `craftstudio` |
| Loader | Fabric |
| Game version | 1.21.11 only |
| Java | Java 21 |
| Language | Java |
| Client/server | Client-side only |
| Source split | Client and common split |
| Data generation | Off initially |
| Primary extraction source | Vanilla base |
| Preview fallback | Project over vanilla |
| Initial categories | Blocks and items |
| Initial preview | Standard JSON block and item models |
| Default extraction mode | Complete bundle |
| Clean-pack option | Unique-only |
| Image editing | External editor |
| Cloud | None |
| Network | None |
| First export types | Folder, ZIP, current instance |
| Project format | CraftStudio metadata plus separate pack root |
| License | MIT recommended, final choice before public release |

---

# 45. MVP Cut Line

CraftStudio is ready for a first useful beta when all statements below are true:

- F8 opens the tool.
- A project can be created and reopened.
- Blocks and items can be searched.
- Crafting table resolves all of its textures and models.
- Furnace resolves lit and unlit forms.
- Complete bundles can be copied into the project.
- Project files override vanilla in preview.
- The preview shows correct face textures.
- Missing dependencies are explained.
- The project exports to a valid ZIP.
- The ZIP loads in Minecraft 1.21.11.

The following are not required for that beta:

- Mob preview
- Sound browser
- Font preview
- Color palette generator
- GUI preview
- Version conversion
- Plugin system
- Marketplace
- Full model editor

---

# 46. Definition of Done for CraftStudio 1.0

## Product

- Core user journey works without external JAR extraction.
- UI is understandable without reading a technical guide.
- Common block and item assets work.
- Unsupported assets are honestly labeled.

## Engineering

- Clean build from a fresh clone.
- Java 21 documented.
- Fabric 1.21.11 versions pinned in Gradle properties.
- Automated tests pass.
- No known project-corrupting bug.
- No broad unreasoned mixins.
- No render-thread file I/O.
- Export is staged and verified.

## Quality

- Crafting table acceptance test passes.
- Furnace acceptance test passes.
- Door or multipart acceptance test passes.
- Basic item acceptance test passes.
- Stateful item acceptance test passes.
- Special renderer limitation test passes.
- Another active resource pack does not contaminate vanilla extraction.

## Release

- Mod icon
- README
- Installation instructions
- Usage guide
- Known limitations
- Changelog
- License
- Modrinth-compatible metadata
- Fabric API dependency declared correctly

---

# 47. Immediate Next Step After This PRD

Generate the official Fabric template with:

```text
Mod Name: CraftStudio
Mod ID: craftstudio
Package: dev.arcn.craftstudio
Minecraft: 1.21.11
Language: Java
Split client and common sources: Enabled
Data Generation: Disabled
Kotlin: Disabled
```

Then perform only Milestone 0:

1. Extract the generated ZIP into a clean project directory.
2. Open it in the IDE.
3. Let Gradle sync.
4. Run the development client.
5. Run the Gradle build.
6. Record the exact generated versions.
7. Commit the untouched baseline.
8. Do not add CraftStudio features until the baseline is proven.

The next implementation prompt should request Milestone 1 only: client bootstrap and the F8 screen.

---

# 48. Final Product Summary

CraftStudio turns resource-pack creation from folder archaeology into a visual workflow.

Its defining feature is not simply extraction. It is understanding.

When a user selects a block or item, CraftStudio resolves the complete asset family:

```text
registry object
→ definition
→ states or item branches
→ models
→ parent models
→ texture variables
→ textures
→ metadata
→ atlases
→ preview
→ validation
→ export
```

Because browsing, preview, validation, and export share this graph, CraftStudio can remain fast for beginners while still exposing the exact technical structure advanced creators need.

The first target is deliberately narrow:

```text
Fabric
Minecraft 1.21.11
Java 21
Client-side
Blocks and items first
```

That narrow foundation is what makes future GUI tools, sound tools, mob previews, model editing, and version conversion realistic rather than brittle.
