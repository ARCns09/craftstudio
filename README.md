# CraftStudio

CraftStudio is a client-side visual IDE for creating Minecraft resource packs
without manually extracting the Minecraft JAR or assembling asset paths by hand.

Version 1.0.0 targets **Minecraft: Java Edition 1.21.11**, **Fabric Loader**, and
**Java 21**.

## What CraftStudio does

- Creates safe, version-specific resource-pack projects.
- Browses the Minecraft 1.21.11 block and item catalog.
- Resolves blockstates, item definitions, model parents, texture variables,
  textures, animation metadata, and atlas dependencies.
- Shows why each resolved dependency is needed.
- Adds complete, unique-only, or custom dependency bundles to a project.
- Previews supported block and item models using project overrides layered over
  clean vanilla assets.
- Watches project files and refreshes affected previews.
- Opens textures in the system or a preferred external editor.
- Validates JSON, images, dependency graphs, atlas usage, metadata, and export
  readiness.
- Exports a root-correct ZIP directly into the current instance's
  `resourcepacks` directory or a custom directory.
- Stages and verifies exports, blocks invalid packs, and backs up an existing
  ZIP before an approved replacement.

## Requirements

- Minecraft: Java Edition 1.21.11
- Fabric Loader 0.19.3 or newer
- Fabric API for Minecraft 1.21.11
- Java 21

Mod Menu is optional. CraftStudio does not require it.

## Installation

1. Install Fabric Loader for Minecraft 1.21.11.
2. Install a compatible Fabric API build.
3. Place `craftstudio-1.0.0.jar` in the instance's `mods` directory.
4. Start Minecraft and verify that CraftStudio appears in the loaded mod list.

CraftStudio is client-only. It is not installed on a dedicated server.

## Quick start

1. Enter a world and press **F8**.
2. Create a project or open an existing CraftStudio project.
3. Open **Browse Vanilla Assets** and search for a block or item.
4. Open its details and review the resolved dependencies.
5. Choose **Add Complete**, **Add Unique Only**, or **Choose Files**.
6. Preview the asset and edit copied textures with an external editor.
7. Run **Validate & Export**.
8. Export to **Current Instance** to create a ZIP directly in the instance's
   `resourcepacks` directory, or choose a custom destination.
9. Enable the ZIP from Minecraft's Resource Packs screen.

F8 is the default key. It can be changed from Minecraft's normal Controls
screen under the CraftStudio keybinding category.

## Project layout

```text
project-name/
├── craftstudio.project.json
├── README.txt
├── pack/
│   ├── pack.mcmeta
│   └── assets/
└── .craftstudio/
    ├── backups/
    ├── cache/
    ├── exports/
    └── logs/
```

Only the contents of `pack/` are eligible for export. CraftStudio project
metadata and `.craftstudio/` internals are rejected if they appear inside the
pack.

## Export safety

Every exported pack is a ZIP. CraftStudio:

- validates the source project;
- copies through a task-owned staging directory;
- validates the staged pack again;
- rejects unsafe ZIP paths and symbolic links;
- verifies that `pack.mcmeta` is at the ZIP root;
- excludes CraftStudio project metadata;
- requires confirmation before replacing an existing output;
- stores a verified backup before replacement; and
- records an export report and SHA-256 hash.

Generated metadata targets resource-pack format `75.0` for Minecraft 1.21.11.

## External editing and reload

The settings screen can use the operating system's default application or a
preferred editor executable. CraftStudio never executes content from the
resource pack itself.

Auto-reload is optional. Manual preview refresh remains available when
Minecraft should not reload all resources after every file save.

## Known limitations

CraftStudio 1.0 focuses on the reliable block and item workflow defined by the
project specification.

- Only Minecraft 1.21.11 is supported.
- The visual catalog currently covers blocks and items.
- GUI textures, entities or mobs, sounds, fonts, paintings, particles, and sky
  assets are planned for later releases.
- CraftStudio is not an image, animation, or model editor; it integrates with
  external tools.
- Special renderers and complex item dispatch types may be detected but cannot
  always be rendered as an ordinary model.
- Imported third-party packs and multi-version conversion are not part of
  version 1.0.
- Export is ZIP-only; unpacked folder export is intentionally unavailable.

## Privacy and security

CraftStudio:

- works without telemetry;
- does not upload project files;
- does not require a network connection;
- reads vanilla game assets and user-selected project files; and
- writes only to CraftStudio paths, project paths, and explicit export
  destinations.

## Building from source

Clone the repository, use Java 21, and run:

```bash
./gradlew clean build
```

The release JAR is written to `build/libs/`. The build also runs the project,
catalog, resource-source, block resolver, item resolver, bundle, preview,
reload, validation, and export verification suites.

Run a development client with:

```bash
./gradlew runClient
```

## Reporting problems

When reporting a problem, include:

- Minecraft, Fabric Loader, Fabric API, and CraftStudio versions;
- whether the asset is a block or item;
- the validation issue code, if present;
- the shortest reproducible steps; and
- the relevant log excerpt without private project contents.

Use the [GitHub issue tracker](https://github.com/ARCns09/craftstudio/issues).

## Version history

### 1.0.0

- Added project creation and recent-project support.
- Added the Minecraft 1.21.11 block and item catalog.
- Added block and item dependency resolution and grouped dependency details.
- Added complete, unique-only, and custom bundle extraction.
- Added interactive block/item previews and external editor integration.
- Added project file watching, targeted refresh, and optional auto-reload.
- Added validation, verified ZIP export, overwrite backups, and export reports.

## License

CraftStudio is **source-available** under the
[CraftStudio Source-Available License 1.0](LICENSE).

You may view, study, compile, privately modify, and privately use the code for
personal, non-commercial purposes. You may also submit patches or pull requests
to the official repository.

You may not publicly upload, reupload, redistribute, republish, sell, bundle,
or release CraftStudio or a modified version without prior written permission
from ARCn09.

Because redistribution is restricted, this is not an OSI-approved open-source
license. The source remains publicly readable and open to contributions.

Author: **ARCn09**
