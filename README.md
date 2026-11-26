# MPIPersist
DoorKeeper persist action for MPI archival storage organisation

Building the jar:
Run the command in the checked out repo ../MPIPersist/MPIPersist: `mvn clean package`

Installation:
Place `MPIPersist-2.0-SNAPSHOT.jar` in the DoorKeeper application classpath.

## Payload layout

MPIPersist recreates the Fedora 3 archive layout on Fedora 7. It walks the
single-parent collection hierarchy loaded by DoorKeeper, reads each
collection's first Dublin Core title, sanitizes every collection title and the
CMDI dataset name, and constructs this logical path:

```
/Collection/Child_collection/Bundle_name/
```

The first matching prefix in `archive-roots-mapping.xml` rewrites that logical
path to its archive filesystem path. All mapped paths must remain below the
configured `archiveRoot`; they do not have to remain below one particular
`archive-data` directory.

MPIPersist 2 keeps the current payload at its existing, unsuffixed regular
filename. On update, the Fedora external-relocation extension atomically moves
the previous current file to a timestamped sibling and rewrites the existing
OCFL version to reference that sibling. The incoming payload then takes the
unsuffixed name:

```
record.wav
record.wav.2026-08-26_11-28-37.123
```

Existing archive files are not migrated in bulk. There are no hard links,
symbolic links, or duplicated payload bytes. The operation is resumable: an
adjacent hidden `.mpipersist-update` journal retains the incoming staging path
and Fedora relocation operation ID after an interruption. Rerunning the SIP
continues that exact update.

The resource handed to DoorKeeper is the unsuffixed regular file. Fedora 7
mementos refer to the timestamped historic file after relocation.
`MPIPersists` must run before the Fedora action which writes `OBJ`; the
Fedora relocation extension must be installed and enabled. `FedoraVersioning`
must remain after the Fedora transaction commit.

Configuration:
The standard persist action configuration in flat-deposit.xml needs to be replaced with something along these lines (adapted for your local paths and CMDI profiles):
```
<action name="persist resources" class="nl.mpi.tla.flat.deposit.action.MPIPersists">
<parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
<parameter name="archiveRoot" value="/mnt/vfs1/archive"/>
<parameter name="xpathDatasetName" value="((/cmd:CMD/cmd:Components/*/cmd:Name | /cmd:CMD/cmd:Components/*/cmd:Title)[normalize-space(.)])[1]"/>
<parameter name="archiveRootMapping" value="{$base}/policies/archive-roots-mapping.xml"/>
</action>
```

For the bundle-detachment workflow, run `MPIDelete` immediately after
`commitTransaction` and before `FedoraVersioning` / Drupal sync:

```xml
<action name="mark removed payloads deleted" class="nl.mpi.tla.flat.deposit.action.MPIDelete">
    <parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
    <parameter name="detachedResources" value="true"/>
    <parameter name="journalDir" value="{$work}/metadata/deleted-payloads"/>
</action>
```

`FedoraDetachResources` must run inside the transaction before CMDI is
replaced. It saves the stable omitted FIDs in `metadata/detached-resources.json`
and republishes them in context memory on retries. `MPIDelete` consumes that
intent after commit; querying the bundle's former relationships would miss
the files once detachment has committed.

Only a removed file with no remaining `isConstituentOf` membership is renamed.
Shared files retain their current filenames. New bundles and updates with no
removals are no-ops. The Fedora object, OBJ binary, Handles and selected access
policy remain available; this workflow does not require `FedoraDelete`.

MPIDelete uses Fedora's external relocation extension to move the regular
current file to `.Deleted.<UTC timestamp>` and rebuild the OCFL version. It
uses no links or payload copies. The SIP's deletion journal records the exact
source, destination and operation ID before requesting relocation, so a retry
can resume even after the original file has moved. Keep these journals with
the SIP until processing completes.

Existing legacy workflows may omit `detachedResources`; in that mode
`MPIDelete` retains its relationship-query discovery and can precede the
legacy `FedoraDelete` action. Do not use that discovery mode after resource
detachment.
