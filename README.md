# MPI-Customizations
This holds all the customised doorkeeper actions specific for MPI

# MPIPersists
DoorKeeper persist action for MPI archival storage organisation	

<action name="persist resources" class="nl.mpi.tla.flat.deposit.action.MPIPersists">
<parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
<parameter name="resourcesDir" value="/app/flat/data"/>
<parameter name="policyFile" value="{$base}/policies/persistence-policy.xml"/>
<parameter name="xpathDatasetName" value="/cmd:CMD[cmd:Header/cmd:MdProfile='clarin.eu:cr1:p_1345561703683']/cmd:Components/cmd:ComicBook/cmd:Title"/>
<parameter name="archiveRootMapping" value="{$base}/policies/archive-roots-mapping.xml"/>
</action>

#MPIDelete
DoorKeeper delete action for MPI archival storage organisation	
(Place this after action FedoraInteract and before FedoraDelete)

<action class="nl.mpi.tla.flat.deposit.action.MPIDelete">
<parameter name="fedoraConfig" value="{$work}/acl/fedora-config.xml"/>
</action>


#MPIChecksum
#TODO
