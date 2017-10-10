# MPIPersist
DoorKeeper persist action for MPI archival storage organisation

Building the jar:
Run the command in the checked out repo ../MPIPersist/MPIPersist: mvn clean install

Installation:
Place the MPIPersist-1.0-SNAPSHOT.jar in the WEB-INF/lib directory of the doorkeeper (flat) web app

Configuration:
The standard persist action configuration in flat-deposit.xml needs to be replaced with something along these lines (adapted for your local paths and CMDI profiles):
```
<action name="persist resources" class="nl.mpi.tla.flat.deposit.action.MPIPersists">
<parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
<parameter name="resourcesDir" value="/app/flat/data"/>
<parameter name="policyFile" value="{$base}/policies/persistence-policy.xml"/>
<parameter name="xpathDatasetName" value="/cmd:CMD[cmd:Header/cmd:MdProfile='clarin.eu:cr1:p_1345561703683']/cmd:Components/cmd:ComicBook/cmd:Title"/>
<parameter name="archiveRootMapping" value="{$base}/policies/archive-roots-mapping.xml"/>
</action>
```
