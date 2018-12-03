/* 
 * Copyright (C) 2015-2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.mpi.tla.flat.deposit.action;

import static com.yourmediashelf.fedora.client.FedoraClient.*;

import com.ibm.icu.text.SimpleDateFormat;
import com.yourmediashelf.fedora.client.request.ModifyDatastream;
import com.yourmediashelf.fedora.client.response.DatastreamProfileResponse;
import com.yourmediashelf.fedora.client.response.FedoraResponse;
import com.yourmediashelf.fedora.client.response.ModifyDatastreamResponse;
import com.yourmediashelf.fedora.client.response.RiSearchResponse;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pavi
 */
public class MPIDelete extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(MPIDelete.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            connect(context);

            SIPInterface sip = context.getSIP();
            
            String sid = sip.getFID().toString().replaceAll("@.*","").replaceAll("#.*","");            
            String sparql = "SELECT ?fid WHERE { ?fid <info:fedora/fedora-system:def/relations-external#isConstituentOf> <info:fedora/"+sid+"> } ";
            logger.debug("SPARQL["+sparql+"]");
            RiSearchResponse resp = riSearch(sparql).format("sparql").execute();
            if (resp.getStatus()==200) {
                XdmNode tpl = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
                logger.debug("RESULT["+tpl.toString()+"]");
                
                for (Iterator<XdmItem> iter=Saxon.xpathIterator(tpl, "//*:results/*:result/*:fid/normalize-space(@uri)");iter.hasNext();) {
                    XdmItem f = iter.next();
                    if (f!=null && !f.getStringValue().isEmpty()) {
                        URI fid = new URI(f.getStringValue().replace("info:fedora/",""));
                        if (sip.getResourceByFID(fid)!=null)
                            continue;
                        logger.debug("DELETE: Resource["+fid+"]");
						
                        //Customization for MPI
						String dsid = "OBJ";
                        DatastreamProfileResponse res = getDatastream(fid.toString(),dsid).execute();
                        if (res.getStatus() != 200)
                           throw new DepositException("Unexpected status[" + res.getStatus()+ "] while interacting with Fedora Commons!");
                        String loc = res.getDatastreamProfile().getDsLocation();
                        String prefix = "file:";
                        if (loc.startsWith(prefix)){
                        	 
                        	 File delTarget = new File(loc.replace(prefix, ""));
                             
                             if(delTarget.exists()){                        
     	                        //Rename file to .delete.timestamp
     	                        String df = new SimpleDateFormat("yyyy-MM-dd_hh-mm-ss").format(new Date());
     							String reName = delTarget.getPath() + ".Deleted." + df;
     							File fileRename = new File(reName);
     							
     							context.registerRollbackEvent(this, "rename", "old", delTarget.getAbsolutePath(), "new", fileRename.getAbsolutePath());
     							
     							if (delTarget.renameTo(fileRename)) {
     								logger.debug("File renamed from " + delTarget + " to " + fileRename);
     								
     								ModifyDatastreamResponse mdsResponse = null;
     								ModifyDatastream mds = modifyDatastream(fid.toString(), dsid);
     	
     								mdsResponse = mds.versionable(false).execute();
     								if (mdsResponse.getStatus() != 200)
     									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
     								
     								context.registerRollbackEvent(this, "updateLocation", "oldPath", delTarget.getPath(), "fid", fid.toString(), "dsid", dsid );
     	
     								mdsResponse = mds.dsLocation("file:/" + delTarget.getPath()).logMessage("Updated " + dsid).execute();
     								
     								if (mdsResponse.getStatus() != 200)
     									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
     	
     								mdsResponse = mds.versionable(true).execute();
     								if (mdsResponse.getStatus() != 200)
     									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
     	
     								logger.debug("Updated FedoraObject[" + fid + "][" + dsid + "]["+ mdsResponse.getLastModifiedDate() + "]");
     								
     								context.registerRollbackEvent(this, "addPID","fid", fid.toString());
     	
     								context.addPID(this.lookupPID(fid),new URI(fid + "#OBJ@" + Global.asOfDateTime(mdsResponse.getLastModifiedDate())));
     							} else {
     								throw new DepositException("FAILED! Failed to rename the resource with same name as new one while updating the resource!");
     							}
     	                    }
                             else {
                             	throw new DepositException("FAILED! File to be deleted: "+delTarget+" does not exist in the filesystem. Fedora and filesystem are out of sync!!!");
                             }					
                        }
                        else {
                        	logger.warn("The res to be deleted is without prefix `file:`. Check!");
                        }	
                    }
                }
            } else
                throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
        return true;
    }
    public void rollback(Context context, List<XdmItem> events) {
		if (events.size() > 0) {
			ModifyDatastreamResponse mdsResponse = null;
			String dsid;
			for (ListIterator<XdmItem> iter = events.listIterator(events.size()); iter.hasPrevious();) {
				XdmItem event = iter.previous();
				try {
					String tpe = Saxon.xpath2string(event, "@type");
					if (tpe.equals("rename")) {
						File origName = new File(Saxon.xpath2string(event, "param[@name='old']/@value"));
						File newName = new File(Saxon.xpath2string(event, "param[@name='new']/@value"));
						if (newName.renameTo(origName)) {
								logger.debug("File renamed from " + newName + " to " + origName);
						}
						else {
							logger.error("File could not be renamed back to Original name during Rollback:");
							logger.error("Original name: "+origName+"  New name: "+newName);
						}
						
					}
					else if (tpe.equals("updateLocation")){
						File path = new File(Saxon.xpath2string(event, "param[@name='oldPath']/@value"));
						String fid = Saxon.xpath2string(event, "param[@name='fid']/@value");
						dsid = Saxon.xpath2string(event, "param[@name='dsid']/@value");
						
						ModifyDatastream mds = modifyDatastream(fid, dsid);
						mdsResponse = mds.versionable(false).execute();
						if (mdsResponse.getStatus() != 200)
							throw new DepositException("Rollback:Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
						mdsResponse = mds.dsLocation("file:/" + path.getPath()).logMessage("Rollback Updated " + dsid).execute();
							
						if (mdsResponse.getStatus() != 200)
							throw new DepositException("Rollback:Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");

						mdsResponse = mds.versionable(true).execute();
						if (mdsResponse.getStatus() != 200)
							throw new DepositException("Rollback:Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");

						logger.debug("Rollback: Updated FedoraObject[" + fid + "][" + dsid + "]["+ mdsResponse.getLastModifiedDate() + "]");
				
					}
					else if (tpe.equals("addPID")){
						String fid = Saxon.xpath2string(event, "param[@name='fid']/@value");
						URI fidURI = new URI(fid);
						context.delPID(this.lookupPID(fidURI));
					}
					else {
	                    logger.error("rollback action[" + this.getName() + "] rollback unknown event[" + tpe + "]!");
	                }
				} catch (Exception ex) {
					logger.error("rollback action[" + this.getName() + "] event[" + event + "] failed!", ex);
				}
			}
		}
	}
    
}
