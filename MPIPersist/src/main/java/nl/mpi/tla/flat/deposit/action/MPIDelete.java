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
import com.yourmediashelf.fedora.client.response.ModifyDatastreamResponse;
import com.yourmediashelf.fedora.client.response.RiSearchResponse;

import java.io.File;
import java.net.URI;
import java.util.Date;
import java.util.Iterator;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
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
     							
     							if (delTarget.renameTo(fileRename)) {
     								logger.debug("File renamed from " + delTarget + " to " + fileRename);
     								
     								ModifyDatastreamResponse mdsResponse = null;
     								ModifyDatastream mds = modifyDatastream(fid.toString(), dsid);
     	
     								mdsResponse = mds.versionable(false).execute();
     								if (mdsResponse.getStatus() != 200)
     									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
     	
     								mdsResponse = mds.dsLocation("file:/" + delTarget.getPath()).logMessage("Updated " + dsid).execute();
     								if (mdsResponse.getStatus() != 200)
     									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
     	
     								mdsResponse = mds.versionable(true).execute();
     								if (mdsResponse.getStatus() != 200)
     									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
     	
     								logger.debug("Updated FedoraObject[" + fid + "][" + dsid + "]["+ mdsResponse.getLastModifiedDate() + "]");
     	
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
    
}
