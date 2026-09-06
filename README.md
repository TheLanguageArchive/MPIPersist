# MPIPersist
DoorKeeper persist action for MPI archival storage organisation

Building the jar:
Run the command in the checked out repo ../MPIPersist/MPIPersist: `mvn clean package`

Installation:
Place `MPIPersist-2.0-SNAPSHOT.jar` in the DoorKeeper application classpath.

## Payload layout

MPIPersist 2 uses an immutable-payload/current-name layout. New ingests are
stored as a timestamped regular file and an unsuffixed symbolic link points to
that file:

```
record.wav -> record.wav.2026-08-26_11-28-37.123
record.wav.2026-08-26_11-28-37.123
```

Existing archive files are not migrated in bulk. On the first update of a
legacy regular `record.wav`, it is renamed in place to a timestamped file; the
new payload receives its own timestamped name; and `record.wav` becomes the
current-name link. No payload bytes are duplicated during promotion.

The resource handed to DoorKeeper is always the timestamped regular file, not
the link. Consequently Fedora 6 proxy content and the mementos subsequently
created by `FedoraVersioning` refer to immutable file URLs. `MPIPersists` must
run before the Fedora action which writes `OBJ`; `FedoraVersioning` must remain
after the Fedora transaction commit.

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

MPIDelete moves the current-name symlink to a `.Deleted.<timestamp>` name,
without changing or copying its immutable payload. For an unpromoted legacy
payload it moves the regular file to that deleted name.
