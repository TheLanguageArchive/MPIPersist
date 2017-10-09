# MPIPersists
DoorKeeper persist action for MPI archival storage organisation	
Upload MPIPersists.java file to the location ../FLAT/docker/add-doorkeeper-to-flat/DoorKeeper/src/main/java/nl/mpi/tla/flat/deposit/action
REPLACE/COMMENT existing Persist action part of file: ../FLAT/docker/add-doorkeeper-to-flat/flat/deposit/flat-deposit.xml with following action statement:

<action name="persist resources" class="nl.mpi.tla.flat.deposit.action.MPIPersists">
<parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
<parameter name="resourcesDir" value="/app/flat/data"/>
<parameter name="policyFile" value="{$base}/policies/persistence-policy.xml"/>
<parameter name="xpathDatasetName" value="/cmd:CMD[cmd:Header/cmd:MdProfile='clarin.eu:cr1:p_1345561703683']/cmd:Components/cmd:ComicBook/cmd:Title"/>
<parameter name="archiveRootMapping" value="{$base}/policies/archive-roots-mapping.xml"/>
</action>

