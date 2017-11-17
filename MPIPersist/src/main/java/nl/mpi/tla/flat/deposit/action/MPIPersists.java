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

import static com.yourmediashelf.fedora.client.FedoraClient.getObjectProfile;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

import javax.xml.transform.stream.StreamSource;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;

import com.ibm.icu.text.SimpleDateFormat;
import static com.yourmediashelf.fedora.client.FedoraClient.*;
import com.yourmediashelf.fedora.client.request.ModifyDatastream;
import com.yourmediashelf.fedora.client.response.GetObjectProfileResponse;
import com.yourmediashelf.fedora.client.response.ModifyDatastreamResponse;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmSequenceIterator;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMD;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.flat.deposit.util.Saxon;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;

/**
 *
 * @author pavi
 */
public class MPIPersists extends FedoraAction {

	private static final Logger logger = LoggerFactory.getLogger(MPIPersists.class.getName());

	@Override
	public boolean perform(Context context) throws DepositException {
		try {
			connect(context);

			String resourcesDir = getParameter("resourcesDir");
			String xpathDSName = getParameter("xpathDatasetName");
			String pre = this.getParameter("namespace", "lat");
			String archRootMapping = this.getParameter("archiveRootMapping");

			// Check the archive root mapping file
			File archRootMap = new File(archRootMapping);
			if (!archRootMap.exists()) {
				logger.error("The Archive root mapping configuration[" + archRootMapping + "] doesn't exist!");
				return false;
			} else if (!archRootMap.isFile()) {
				logger.error("The Archive root mapping configuration[" + archRootMapping + "] isn't a file!");
				return false;
			} else if (!archRootMap.canRead()) {
				logger.error("The Archive root mapping configuration[" + archRootMapping + "] can't be read!");
				return false;
			}
			logger.debug("Fedora configuration[" + archRootMap.getAbsolutePath() + "]");

			CMD sip = (CMD) context.getSIP();

			String path = Saxon.xpath2string(Saxon.wrapNode(sip.getRecord()), xpathDSName, null, NAMESPACES);
			if (path == null || path.isEmpty()) {
				throw new DepositException("SIP Name is unknown!");
			}

			path = StringUtils.stripAccents(path);
			path = path.replaceAll("[^a-zA-Z0-9]", "_");

			Set<Collection> cols = context.getSIP().getCollections(false);
			while (!cols.isEmpty()) {
				logger.debug("cols size: " + cols.size());
				for (Collection col : cols) {
					logger.debug("FID : " + col.getFID(true));
				}
				if (cols.size() > 1) {
					throw new DepositException("MPI doesn't do more than one parent collection!");
				}
				Collection col = cols.iterator().next();
				GetObjectProfileResponse res = getObjectProfile(col.getFID(true).toString()).execute();
				if (res.getStatus() != 200)
					throw new DepositException(
							"Unexpected status[" + res.getStatus() + "] while interacting with Fedora Commons!");
				String lbl = res.getLabel();
				if (lbl == null || lbl.isEmpty()) {
					throw new DepositException("Collection label is unknown!");
				}

				// remove diacritics from the lbl string
				lbl = StringUtils.stripAccents(lbl);
				lbl = lbl.replaceAll("[^a-zA-Z0-9]", "_");
				path = lbl + "/" + path;
				cols = col.getParentCollections();
			}

			path += "/";
			
			//Replace the fedora path with file system path based on archive-roots-mapping.xml
			XdmNode nArchRootMap = Saxon.buildDocument(new StreamSource(archRootMap));
			XdmNode mapping = (XdmNode) Saxon.xpathSingle(nArchRootMap,"//mapping[starts-with('"+path+"',sanitized-fedora-path)][1]");
			if (mapping!=null){
				String fedoraPath = Saxon.xpath2string(mapping, "./sanitized-fedora-path"); 
				String fileSysPath = Saxon.xpath2string(mapping, "./file-system-path");
				logger.debug("Path: "+path);
				logger.debug("The sanitized fedora path(archive-roots-mapping.xml): "+fedoraPath);
				logger.debug("Path in file system(archive-roots-mapping.xml): "+fileSysPath);
				path = path.replace(fedoraPath,fileSysPath);
				logger.debug("Path after replacing: "+path);
			}
			else {
				throw new DepositException("FAILED! Path: "+path+" is not specified in archive-roots-mapping.xml file");
			}

			//path = resourcesDir + "/" + path;--  Not required anymore as the path is now determined based on the archive-roots-mapping.xml

			Path dirPath = Paths.get(path);

			if (!Files.exists(dirPath)) {
				try {
					Files.createDirectories(dirPath);
					logger.debug("Directory structure created! " + dirPath);
				} catch (Exception ex) {
					throw new DepositException("Creation of directories failed:" + ex.getMessage());

				}
			}

			for (Resource res : sip.getResources()) {
				if (res.isUpdate() || res.isInsert()) {
					File file = res.getFile();

					if (file == null) {
						throw new DepositException("Unexpected status! No file found in resource");
					}

					String fileName = file.getName();
					File target = new File(path + "/" + fileName);
					while (target.exists()) {
						if (res.isUpdate()) {
							String df = new SimpleDateFormat("yyyy-MM-dd_hh-mm-ss").format(new Date());
							File oldFile = new File(target.getPath());
							String reName = oldFile.getPath() + "." + df;
							File oldFileRename = new File(reName);

							if (oldFile.renameTo(oldFileRename)) {
								logger.debug("File renamed from " + oldFile + " to " + oldFileRename);

								String fid = res.getFID(true).toString();
								String dsid = "OBJ";
								ModifyDatastreamResponse mdsResponse = null;
								ModifyDatastream mds = modifyDatastream(fid, dsid);

								mdsResponse = mds.versionable(false).execute();
								if (mdsResponse.getStatus() != 200)
									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");

								mdsResponse = mds.dsLocation("file:/" + oldFile.getPath()).logMessage("Updated " + dsid).execute();
								if (mdsResponse.getStatus() != 200)
									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");

								mdsResponse = mds.versionable(true).execute();
								if (mdsResponse.getStatus() != 200)
									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");

								logger.debug("Updated FedoraObject[" + fid + "][" + dsid + "]["+ mdsResponse.getLastModifiedDate() + "]");

								// register the update of the PID for the old
								// version of the resource
								context.addPID(this.lookupPID(res.getFID(true)), new URI(fid + "#OBJ@" + Global.asOfDateTime(mdsResponse.getLastModifiedDate())));
							} else {
								throw new DepositException("FAILED! Failed to rename the resource with same name as new one while updating the resource!");
							}
						} else {
							throw new DepositException("File path [" + target + "] clash for Resource[" + res.getFile()+ "] during update");
						}
					}
					Files.move(res.getFile().toPath(), target.toPath());
					logger.debug("Moved file from :" + res.getFile().toPath() + " to: " + target.getPath());
					res.setFile(target);
				} else {
					logger.debug("The res was neither for insert not for update. Persist action did not perform on any res!");
				}
			}

		}
		catch(Exception ex)
	    {
			throw new DepositException("Couldn't persist the resources!", ex);
		}return true;
   }
}