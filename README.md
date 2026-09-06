# MPIPersist
DoorKeeper persist action for MPI archival storage organisation

Building the jar:
Run the command in the checked out repo ../MPIPersist/MPIPersist: `mvn clean package`

Installation:
Place `MPIPersist-2.0-SNAPSHOT.jar` in the DoorKeeper application classpath.

## Payload layout

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
<parameter name="xpathDatasetName" value="/cmd:CMD[cmd:Header/cmd:MdProfile='clarin.eu:cr1:p_1345561703683']/cmd:Components/cmd:ComicBook/cmd:Title"/>
<parameter name="archiveRootMapping" value="{$base}/policies/archive-roots-mapping.xml"/>
</action>
```

The MPIDelete action configuration in flat-deposit.xml needs to be placed with following lines before the action FedoraDelete (adapted for your local paths and CMDI profiles):
```
<action class="nl.mpi.tla.flat.deposit.action.MPIDelete">
	<parameter name="fedoraConfig" value="{$work}/acl/fedora-config.xml"/>
</action>
```

MPIDelete uses the same Fedora relocation extension to move the regular
current file to `.Deleted.<timestamp>` and rebuild the OCFL version. It does
not use links or copy payload bytes.
