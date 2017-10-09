# MPIPersists
DoorKeeper persist action for MPI archival storage organisation	
How-to Build the code:
Run the command in the checked out repo ../MPIPersist/MPIPersist: mvn clean install
Place the MPIPersist-1.0-SNAPSHOT.jar in the docker path: /var/www/fedora/tomcat/webapps/flat/WEB-INF/lib
restart tomcat: supervisorctl restart tomcat

REPLACE/COMMENT existing Persist action part of file: ../FLAT/docker/add-doorkeeper-to-flat/flat/deposit/flat-deposit.xml with following action statement:

<action name="persist resources" class="nl.mpi.tla.flat.deposit.action.MPIPersists">
<parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
<parameter name="resourcesDir" value="/app/flat/data"/>
<parameter name="policyFile" value="{$base}/policies/persistence-policy.xml"/>
<parameter name="xpathDatasetName" value="/cmd:CMD[cmd:Header/cmd:MdProfile='clarin.eu:cr1:p_1345561703683']/cmd:Components/cmd:ComicBook/cmd:Title"/>
<parameter name="archiveRootMapping" value="{$base}/policies/archive-roots-mapping.xml"/>
</action>

