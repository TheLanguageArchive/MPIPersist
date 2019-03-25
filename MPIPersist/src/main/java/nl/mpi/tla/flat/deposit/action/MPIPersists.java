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

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import javax.xml.transform.stream.StreamSource;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;

import com.ibm.icu.text.SimpleDateFormat;
import static com.yourmediashelf.fedora.client.FedoraClient.*;
import com.yourmediashelf.fedora.client.FedoraClientException;
import com.yourmediashelf.fedora.client.request.ModifyDatastream;
import com.yourmediashelf.fedora.client.response.GetObjectProfileResponse;
import com.yourmediashelf.fedora.client.response.ModifyDatastreamResponse;
import com.yourmediashelf.fedora.client.response.RiSearchResponse;
import java.net.URISyntaxException;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
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

			path = getCollectionPath(sip) + "/" + path;

			path += "/";

			// Replace the fedora path with file system path based on
			// archive-roots-mapping.xml
			XdmNode nArchRootMap = Saxon.buildDocument(new StreamSource(archRootMap));
			XdmNode mapping = (XdmNode) Saxon.xpathSingle(nArchRootMap,"//mapping[starts-with('" + path + "',sanitized-fedora-path)][1]");
			if (mapping != null) {
				String fedoraPath = Saxon.xpath2string(mapping, "./sanitized-fedora-path");
				String fileSysPath = Saxon.xpath2string(mapping, "./file-system-path");
				logger.debug("Path: " + path);
				logger.debug("The sanitized fedora path(archive-roots-mapping.xml): " + fedoraPath);
				logger.debug("Path in file system(archive-roots-mapping.xml): " + fileSysPath);
				path = path.replace(fedoraPath, fileSysPath);
				logger.debug("Path after replacing: " + path);
			} else {
				throw new DepositException("FAILED! Path: " + path + " is not specified in archive-roots-mapping.xml file");
			}

			Path dirPath = Paths.get(path);
			Iterator<Path> iPath = dirPath.iterator();
			Path p = Paths.get("/");

			for (Path part : dirPath) {
				p = (p == null ? part : p.resolve(part));
				if (!Files.exists(p)) {
					try {
						context.registerRollbackEvent(this, "mkdir", "dir", p.toString());
						Files.createDirectory(p);
						logger.debug("Directory created! " + p);
					} catch (Exception ex) {
						throw new DepositException("Creation of directories failed:" + ex.getMessage());

					}
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
								if (mdsResponse.getStatus() != 200) {
									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
								}

								mdsResponse = mds.dsLocation("file:/" + oldFile.getPath()).logMessage("Updated " + dsid).execute();
								if (mdsResponse.getStatus() != 200) {
									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
								}

								context.registerRollbackEvent(this, "updateLocation", "oldPath", oldFile.getPath(),"fid", fid, "dsid", dsid);

								mdsResponse = mds.versionable(true).execute();
								if (mdsResponse.getStatus() != 200) {
									throw new DepositException("Unexpected status[" + mdsResponse.getStatus()+ "] while interacting with Fedora Commons!");
								}

								logger.debug("Updated FedoraObject[" + fid + "][" + dsid + "]["+ mdsResponse.getLastModifiedDate() + "]");

								// register the update of the PID for the old
								// version of the resource
								context.registerRollbackEvent(this, "addPID");
								context.addPID(this.lookupPID(res.getFID(true)), new URI(fid + "#OBJ@" + Global.asOfDateTime(mdsResponse.getLastModifiedDate())));
							} else {
								throw new DepositException("FAILED! Failed to rename the resource with same name as new one while updating the resource!");
							}
						} else {
							throw new DepositException("File path [" + target + "] clash for Resource[" + res.getFile()+ "] during update");
						}
					}

					context.registerRollbackEvent(this, "move", "from", res.getFile().toPath().toString(), "to",target.toPath().toString());

					Files.move(res.getFile().toPath(), target.toPath());
					logger.debug("Moved file from :" + res.getFile().toPath() + " to: " + target.getPath());
					res.setFile(target);
				} else {
					logger.debug("The res was neither for insert not for update. MPIPersist action did not perform on the res!- "+res.getFID().toString());
				}
			}

		} catch (Exception ex) {
			throw new DepositException("Couldn't persist the resources!", ex);
		}
		return true;
	}

	private String getCollectionPath(CMD sip) throws FedoraClientException, SaxonApiException, URISyntaxException, DepositException {
		String path = "";
		String pFid = null;
		Set colls = sip.getCollections(false);
		if (colls.size() > 1) {
			throw new DepositException("MPI doesn't do more than one parent collection!");
		} else if (!colls.isEmpty()) {
			pFid = ((Collection) colls.iterator().next()).getFID(true).toString();
		}
		if (pFid != null) {
			path = getCollectionPath(pFid) + "/" + getSanitizedCollectionLabel(pFid);
		}
		return path;
	}

	private String getCollectionPath(String fid) throws FedoraClientException, SaxonApiException, URISyntaxException, DepositException {
		String path = "";
		String sparql = "SELECT ?fid WHERE { <info:fedora/" + fid+ "> <info:fedora/fedora-system:def/relations-external#isMemberOfCollection> ?fid } ";
		logger.debug("SPARQL[" + sparql + "]");
		RiSearchResponse resp = riSearch(sparql).format("sparql").execute();
		if (resp.getStatus() == 200) {
			XdmNode tpl = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
			logger.debug("RESULT[" + tpl.toString() + "]");
			for (Iterator<XdmItem> iter = Saxon.xpathIterator(tpl,"normalize-space(//*:results/*:result/*:fid/@uri)"); iter.hasNext();) {
				XdmItem n = iter.next();
				String f = n.getStringValue();
				if (f != null && !f.isEmpty()) {
					String pFid = f.replace("info:fedora/", "").replaceAll("#.*", "");

					path = getCollectionPath(pFid) + "/" + getSanitizedCollectionLabel(pFid);

					if (iter.hasNext()) {
						throw new DepositException("MPI doesn't do more than one parent collection!");
					}
					break; // get out of the loop
				}
			}
		} else
			throw new DepositException("Unexpected status[" + resp.getStatus() + "] while interacting with Fedora Commons!");
		return path;
	}

	private String getSanitizedCollectionLabel(String fid) throws FedoraClientException, SaxonApiException, URISyntaxException, DepositException {
		GetObjectProfileResponse res = getObjectProfile(fid).execute();
		if (res.getStatus() != 200) {
			throw new DepositException("Unexpected status[" + res.getStatus() + "] while interacting with Fedora Commons!");
		}

		String lbl = res.getLabel();
		if (lbl == null || lbl.isEmpty()) {
			throw new DepositException("Collection label is unknown!");
		}

		// remove diacritics from the lbl string
		lbl = StringUtils.stripAccents(lbl);
		lbl = lbl.replaceAll("[^a-zA-Z0-9]", "_");

		return lbl;
	}

	public void rollback(Context context, List<XdmItem> events) {
		if (events.size() > 0) {
			ModifyDatastreamResponse mdsResponse = null;
			String fid = null;
			String dsid;
			for (ListIterator<XdmItem> iter = events.listIterator(events.size()); iter.hasPrevious();) {
				XdmItem event = iter.previous();
				try {
					String tpe = Saxon.xpath2string(event, "@type");
					if (tpe.equals("move")) {
						File from = new File(Saxon.xpath2string(event, "param[@name='from']/@value"));
						File to = new File(Saxon.xpath2string(event, "param[@name='to']/@value"));
						if (to.exists() && to.isFile() && to.canWrite() && !from.exists()) {
							Files.move(to.toPath(), from.toPath());
							logger.debug("rollback action[" + this.getName() + "] event[" + tpe + "] moved [" + to+ "] back to [" + from + "]");
						} else {
							logger.error("rollback action[" + this.getName() + "] event["+ Saxon.xpath2string(event, "@type") + "] failed source[" + from+ "] and destination[" + to + "] can't be restored!");
						}
					} else if (tpe.equals("mkdir")) {
						File dir = new File(Saxon.xpath2string(event, "param[@name='dir']/@value"));
						if (dir.list().length == 0) {
							logger.info("Directory["+dir.toPath()+" is empty! (Safe to delete)");
							if (Files.deleteIfExists(dir.toPath())) 
								logger.debug("rollback action[" + this.getName() + "] event[" + tpe + "] removed dir[" + dir + "]");	
							else 
								logger.debug("But-- rollback action[" + this.getName() + "] event[" + tpe + "] did not remove dir[" + dir + "]");
						}
						else
							logger.info("Directory["+dir.toPath()+" is not empty!!!");
					} else if (tpe.equals("updateLocation")) {
						File path = new File(Saxon.xpath2string(event, "param[@name='oldPath']/@value"));
						fid = Saxon.xpath2string(event, "param[@name='fid']/@value");
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

					} else if (tpe.equals("addPID")) {
						URI fidURI = new URI(fid);
						context.addPID(this.lookupPID(fidURI),new URI(fid + "#OBJ@" + Global.asOfDateTime(mdsResponse.getLastModifiedDate())));
					} else {
						logger.error("rollback action[" + this.getName() + "] rollback unknown event[" + tpe + "]!");
					}
				} catch (Exception ex) {
					logger.error("rollback action[" + this.getName() + "] event[" + event + "] failed!", ex);
				}
			}
		}
	}

}
