package com.cloud.storage.template;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.api.storage.CreateEntityDownloadURLAnswer;
import com.cloud.agent.api.storage.CreateEntityDownloadURLCommand;
import com.cloud.agent.api.storage.DeleteEntityDownloadURLAnswer;
import com.cloud.agent.api.storage.DeleteEntityDownloadURLCommand;
import com.cloud.agent.api.storage.UploadAnswer;
import com.cloud.agent.api.storage.UploadProgressCommand;
import com.cloud.agent.api.storage.UploadCommand;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.StorageResource;
import com.cloud.storage.UploadVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.template.TemplateUploader.UploadCompleteCallback;
import com.cloud.storage.template.TemplateUploader.Status;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.UUID;
import com.cloud.utils.component.Adapters;
import com.cloud.utils.component.ComponentLocator;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

public class UploadManagerImpl implements UploadManager {


   public class Completion implements UploadCompleteCallback {
        private final String jobId;

        public Completion(String jobId) {
            this.jobId = jobId;
        }

        @Override
        public void uploadComplete(Status status) {
            setUploadStatus(jobId, status);
        }
    }
   
   private static class UploadJob {
       private final TemplateUploader tu;
       private final String jobId;
       private final String name;
       private final ImageFormat format;
       private String tmpltPath;
       private String description;
       private String checksum;
       private Long accountId;
       private String installPathPrefix;
       private long templatesize;
       private long id;

       public UploadJob(TemplateUploader tu, String jobId, long id, String name, ImageFormat format, boolean hvm, Long accountId, String descr, String cksum, String installPathPrefix) {
           super();
           this.tu = tu;
           this.jobId = jobId;
           this.name = name;
           this.format = format;
           this.accountId = accountId;
           this.description = descr;
           this.checksum = cksum;
           this.installPathPrefix = installPathPrefix;
           this.templatesize = 0;
           this.id = id;
       }

       public TemplateUploader getTd() {
           return tu;
       }

       public String getDescription() {
           return description;
       }

       public String getChecksum() {
           return checksum;
       }

       public UploadJob(TemplateUploader td, String jobId, UploadCommand cmd) {
           this.tu = td;
           this.jobId = jobId;
           this.name = cmd.getName();
           this.format = cmd.getFormat();           
       }

       public TemplateUploader getTemplateUploader() {
           return tu;
       }

       public String getJobId() {
           return jobId;
       }

       public String getTmpltName() {
           return name;
       }

       public ImageFormat getFormat() {
           return format;
       }

       public Long getAccountId() {
           return accountId;
       }

       public long getId() {
           return id;
       }

       public void setTmpltPath(String tmpltPath) {
           this.tmpltPath = tmpltPath;
       }

       public String getTmpltPath() {
           return tmpltPath;
       }

       public String getInstallPathPrefix() {
           return installPathPrefix;
       }

       public void cleanup() {
           if (tu != null) {
               String upldPath = tu.getUploadLocalPath();
               if (upldPath != null) {
                   File f = new File(upldPath);
                   f.delete();
               }
           }
       }

       public void setTemplatesize(long templatesize) {
           this.templatesize = templatesize;
       }

       public long getTemplatesize() {
           return templatesize;
       }
   }
       public static final Logger s_logger = Logger.getLogger(UploadManagerImpl.class);
       private ExecutorService threadPool;
       private final Map<String, UploadJob> jobs = new ConcurrentHashMap<String, UploadJob>();
       private String parentDir;
       private Adapters<Processor> _processors;
       private String publicTemplateRepo;
       private StorageLayer _storage;
       private int installTimeoutPerGig;
       private boolean _sslCopy;
       private String _name;
       private boolean hvm;
     	   
	
	@Override
	public String uploadPublicTemplate(long id, String url, String name,
			ImageFormat format, Long accountId, String descr,
			String cksum, String installPathPrefix, String userName,
			String passwd, long templateSizeInBytes) {		
		
        UUID uuid = new UUID();
        String jobId = uuid.toString();

        String completePath = parentDir + File.separator + installPathPrefix;
        s_logger.debug("Starting upload from " + completePath);
        
        URI uri;
		try {
		    uri = new URI(url);
		} catch (URISyntaxException e) {
		    s_logger.error("URI is incorrect: " + url);
		    throw new CloudRuntimeException("URI is incorrect: " + url);
		}
		TemplateUploader tu;
		if ((uri != null) && (uri.getScheme() != null)) {
		    if (uri.getScheme().equalsIgnoreCase("ftp")) {
		        tu = new FtpTemplateUploader(completePath, url, new Completion(jobId), templateSizeInBytes);                                
		    } else {
		    	s_logger.error("Scheme is not supported " + url);
		        throw new CloudRuntimeException("Scheme is not supported " + url);
		    }
		} else {
		    s_logger.error("Unable to download from URL: " + url);
		    throw new CloudRuntimeException("Unable to download from URL: " + url);
		}
		UploadJob uj = new UploadJob(tu, jobId, id, name, format, hvm, accountId, descr, cksum, installPathPrefix);
		jobs.put(jobId, uj);
		threadPool.execute(tu);

		return jobId;
				
	}

	@Override
	public String getUploadError(String jobId) {
        UploadJob uj = jobs.get(jobId);
        if (uj != null) {
            return uj.getTemplateUploader().getUploadError();
        }
        return null;
	}

	@Override
	public int getUploadPct(String jobId) {
		UploadJob uj = jobs.get(jobId);
        if (uj != null) {
            return uj.getTemplateUploader().getUploadPercent();
        }
        return 0;
	}

	@Override
	public Status getUploadStatus(String jobId) {
        UploadJob job = jobs.get(jobId);
        if (job != null) {
            TemplateUploader tu = job.getTemplateUploader();
            if (tu != null) {
                return tu.getStatus();
            }
        }
        return Status.UNKNOWN;
	}
	
    public static UploadVO.Status convertStatus(Status tds) {
        switch (tds) {
        case ABORTED:
            return UploadVO.Status.NOT_UPLOADED;
        case UPLOAD_FINISHED:
            return UploadVO.Status.UPLOAD_IN_PROGRESS;
        case IN_PROGRESS:
            return UploadVO.Status.UPLOAD_IN_PROGRESS;
        case NOT_STARTED:
            return UploadVO.Status.NOT_UPLOADED;
        case RECOVERABLE_ERROR:
            return UploadVO.Status.NOT_UPLOADED;
        case UNKNOWN:
            return UploadVO.Status.UNKNOWN;
        case UNRECOVERABLE_ERROR:
            return UploadVO.Status.UPLOAD_ERROR;
        case POST_UPLOAD_FINISHED:
            return UploadVO.Status.UPLOADED;
        default:
            return UploadVO.Status.UNKNOWN;
        }
    }

    @Override
    public com.cloud.storage.UploadVO.Status getUploadStatus2(String jobId) {
        return convertStatus(getUploadStatus(jobId));
    }
	@Override
	public String getPublicTemplateRepo() {
		// TODO Auto-generated method stub
		return null;
	}

    private UploadAnswer handleUploadProgressCmd(UploadProgressCommand cmd) {
        String jobId = cmd.getJobId();
        UploadAnswer answer;
        UploadJob uj = null;
        if (jobId != null)
            uj = jobs.get(jobId);
        if (uj == null) {           
           return new UploadAnswer(null, 0, "Cannot find job", com.cloud.storage.UploadVO.Status.UNKNOWN, "", "", 0);            
        }
        TemplateUploader td = uj.getTemplateUploader();
        switch (cmd.getRequest()) {
        case GET_STATUS:
            break;
        case ABORT:
            td.stopUpload();
            sleep();
            break;
        /*case RESTART:
            td.stopUpload();
            sleep();
            threadPool.execute(td);
            break;*/
        case PURGE:
            td.stopUpload();
            answer = new UploadAnswer(jobId, getUploadPct(jobId), getUploadError(jobId), getUploadStatus2(jobId), getUploadLocalPath(jobId), getInstallPath(jobId), getUploadTemplateSize(jobId));
            jobs.remove(jobId);
            return answer;
        default:
            break; // TODO
        }
        return new UploadAnswer(jobId, getUploadPct(jobId), getUploadError(jobId), getUploadStatus2(jobId), getUploadLocalPath(jobId), getInstallPath(jobId),
                getUploadTemplateSize(jobId));
    }	
	
    @Override
    public UploadAnswer handleUploadCommand(UploadCommand cmd) {
    	s_logger.warn("Handling the upload " +cmd.getInstallPath() + " " + cmd.getId());
        if (cmd instanceof UploadProgressCommand) {
            return handleUploadProgressCmd((UploadProgressCommand) cmd);
        }

        String user = null;
        String password = null;
        String jobId = uploadPublicTemplate(cmd.getId(), cmd.getUrl(), cmd.getName(), 
        									cmd.getFormat(), cmd.getAccountId(), cmd.getDescription(),
        									cmd.getChecksum(), cmd.getInstallPath(), user, password,
        									cmd.getTemplateSizeInBytes());
        sleep();
        if (jobId == null) {
            return new UploadAnswer(null, 0, "Internal Error", com.cloud.storage.UploadVO.Status.UPLOAD_ERROR, "", "", 0);
        }
        return new UploadAnswer(jobId, getUploadPct(jobId), getUploadError(jobId), getUploadStatus2(jobId), getUploadLocalPath(jobId), getInstallPath(jobId),
                getUploadTemplateSize(jobId));
    }
    
    @Override
    public CreateEntityDownloadURLAnswer handleCreateEntityURLCommand(CreateEntityDownloadURLCommand cmd){
        
        // Create the directory structure so that its visible under apache server root
        Script command = new Script("mkdir", s_logger);
        command.add("-p");
        command.add("/var/www/html/");
        String result = command.execute();
        if (result != null) {
            String errorString = "Error in creating directory =" + result;
            s_logger.warn(errorString);
            return new CreateEntityDownloadURLAnswer(errorString, CreateEntityDownloadURLAnswer.RESULT_FAILURE);
        }
        
        // Create a symbolic link from the actual directory to the template location
        cmd.getInstallPath();
        command = new Script("/bin/bash", s_logger);
        command.add("-c");
        command.add("ln -sf " + publicTemplateRepo + cmd.getInstallPath() + " /var/www/html/");
        result = command.execute();
        if (result != null) {
            String errorString = "Error in linking  err=" + result; 
            s_logger.warn(errorString);
            return new CreateEntityDownloadURLAnswer(errorString, CreateEntityDownloadURLAnswer.RESULT_FAILURE);
        }
        
        return new CreateEntityDownloadURLAnswer("", CreateEntityDownloadURLAnswer.RESULT_SUCCESS);
        
    }
    
    @Override
    public DeleteEntityDownloadURLAnswer handleDeleteEntityDownloadURLCommand(DeleteEntityDownloadURLCommand cmd){

        s_logger.debug("handleDeleteEntityDownloadURLCommand "+cmd.getPath());
        Script command = new Script("/bin/bash", s_logger);
        command.add("-c");
        command.add("unlink /var/www/html/"+cmd.getPath());
        String result = command.execute();
        if (result != null) {
            String errorString = "Error in deleting =" + result;
            s_logger.warn(errorString);
            return new DeleteEntityDownloadURLAnswer(errorString, CreateEntityDownloadURLAnswer.RESULT_FAILURE);
        }
        return new DeleteEntityDownloadURLAnswer("", CreateEntityDownloadURLAnswer.RESULT_SUCCESS);
    }

	private String getInstallPath(String jobId) {
		// TODO Auto-generated method stub
		return null;
	}

	private String getUploadLocalPath(String jobId) {
		// TODO Auto-generated method stub
		return null;
	}

	private long getUploadTemplateSize(String jobId){
		UploadJob uj = jobs.get(jobId);
        if (uj != null) {
            return uj.getTemplatesize();
        }
        return 0;
	}

	@Override
	public String setRootDir(String rootDir, StorageResource storage) {
        this.publicTemplateRepo = rootDir + publicTemplateRepo;
        return null;
	}

	@Override
	public boolean configure(String name, Map<String, Object> params)
			throws ConfigurationException {
        _name = name;

        String value = null;

        _storage = (StorageLayer) params.get(StorageLayer.InstanceConfigKey);
        if (_storage == null) {
            value = (String) params.get(StorageLayer.ClassConfigKey);
            if (value == null) {
                throw new ConfigurationException("Unable to find the storage layer");
            }

            Class<StorageLayer> clazz;
            try {
                clazz = (Class<StorageLayer>) Class.forName(value);
            } catch (ClassNotFoundException e) {
                throw new ConfigurationException("Unable to instantiate " + value);
            }
            _storage = ComponentLocator.inject(clazz);
        }
        String useSsl = (String)params.get("sslcopy");
        if (useSsl != null) {
        	_sslCopy = Boolean.parseBoolean(useSsl);
        	
        }
        configureFolders(name, params);
        String inSystemVM = (String)params.get("secondary.storage.vm");
        if (inSystemVM != null && "true".equalsIgnoreCase(inSystemVM)) {
        	//s_logger.info("UploadManager: starting additional services since we are inside system vm");
        	//startAdditionalServices();
        	//blockOutgoingOnPrivate();
        }

        value = (String) params.get("install.timeout.pergig");
        this.installTimeoutPerGig = NumbersUtil.parseInt(value, 15 * 60) * 1000;

        value = (String) params.get("install.numthreads");
        final int numInstallThreads = NumbersUtil.parseInt(value, 10);        

        String scriptsDir = (String) params.get("template.scripts.dir");
        if (scriptsDir == null) {
            scriptsDir = "scripts/storage/secondary";
        }

        List<Processor> processors = new ArrayList<Processor>();
        _processors = new Adapters<Processor>("processors", processors);
        Processor processor = new VhdProcessor();
        
        processor.configure("VHD Processor", params);
        processors.add(processor);
        
        processor = new IsoProcessor();
        processor.configure("ISO Processor", params);
        processors.add(processor);
        
        processor = new QCOW2Processor();
        processor.configure("QCOW2 Processor", params);
        processors.add(processor);
        // Add more processors here.
        threadPool = Executors.newFixedThreadPool(numInstallThreads);

        return true;
	}
	
	protected void configureFolders(String name, Map<String, Object> params) throws ConfigurationException {
        parentDir = (String) params.get("template.parent");
        if (parentDir == null) {
            throw new ConfigurationException("Unable to find the parent root for the templates");
        }

        String value = (String) params.get("public.templates.root.dir");
        if (value == null) {
            value = TemplateConstants.DEFAULT_TMPLT_ROOT_DIR;
        }
               
		if (value.startsWith(File.separator)) {
            publicTemplateRepo = value;
        } else {
            publicTemplateRepo = parentDir + File.separator + value;
        }
        
        if (!publicTemplateRepo.endsWith(File.separator)) {
            publicTemplateRepo += File.separator;
        }
        
        publicTemplateRepo += TemplateConstants.DEFAULT_TMPLT_FIRST_LEVEL_DIR;
        
        if (!_storage.mkdirs(publicTemplateRepo)) {
            throw new ConfigurationException("Unable to create public templates directory");
        }
    }

	@Override
	public String getName() {
		return _name;
	}

	@Override
	public boolean start() {
		return true;
	}

	@Override
	public boolean stop() {
		return true;
	}

    /**
     * Get notified of change of job status. Executed in context of uploader thread
     * 
     * @param jobId
     *            the id of the job
     * @param status
     *            the status of the job
     */
    public void setUploadStatus(String jobId, Status status) {
        UploadJob uj = jobs.get(jobId);
        if (uj == null) {
            s_logger.warn("setUploadStatus for jobId: " + jobId + ", status=" + status + " no job found");
            return;
        }
        TemplateUploader tu = uj.getTemplateUploader();
        s_logger.warn("Upload Completion for jobId: " + jobId + ", status=" + status);
        s_logger.warn("UploadedBytes=" + tu.getUploadedBytes() + ", error=" + tu.getUploadError() + ", pct=" + tu.getUploadPercent());

        switch (status) {
        case ABORTED:
        case NOT_STARTED:
        case UNRECOVERABLE_ERROR:
            // TODO
            uj.cleanup();
            break;
        case UNKNOWN:
            return;
        case IN_PROGRESS:
            s_logger.info("Resuming jobId: " + jobId + ", status=" + status);
            tu.setResume(true);
            threadPool.execute(tu);
            break;
        case RECOVERABLE_ERROR:
            threadPool.execute(tu);
            break;
        case UPLOAD_FINISHED:
            tu.setUploadError("Upload success, starting install ");
            String result = postUpload(jobId);
            if (result != null) {
                s_logger.error("Failed post upload script: " + result);
                tu.setStatus(Status.UNRECOVERABLE_ERROR);
                tu.setUploadError("Failed post upload script: " + result);
            } else {
            	s_logger.warn("Upload completed successfully at " + new SimpleDateFormat().format(new Date()));
                tu.setStatus(Status.POST_UPLOAD_FINISHED);
                tu.setUploadError("Upload completed successfully at " + new SimpleDateFormat().format(new Date()));
            }
            uj.cleanup();
            break;
        default:
            break;
        }
    }

	private String postUpload(String jobId) {
		return null;
	}

    private void sleep() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void blockOutgoingOnPrivate() {
    	Script command = new Script("/bin/bash", s_logger);
    	String intf = "eth1";
    	command.add("-c");
    	command.add("iptables -A OUTPUT -o " + intf + " -p tcp -m state --state NEW -m tcp --dport " + "80" + " -j REJECT;" +
    			"iptables -A OUTPUT -o " + intf + " -p tcp -m state --state NEW -m tcp --dport " + "443" + " -j REJECT;");

    	String result = command.execute();
    	if (result != null) {
    		s_logger.warn("Error in blocking outgoing to port 80/443 err=" + result );
    		return;
    	}		
	}

  private void startAdditionalServices() {
    	
    	Script command = new Script("/bin/bash", s_logger);
		command.add("-c");
    	command.add("service httpd stop ");
    	String result = command.execute();
    	if (result != null) {
    		s_logger.warn("Error in stopping httpd service err=" + result );
    	}
    	String port = Integer.toString(TemplateConstants.DEFAULT_TMPLT_COPY_PORT);
    	String intf = TemplateConstants.DEFAULT_TMPLT_COPY_INTF;
    	
    	command = new Script("/bin/bash", s_logger);
		command.add("-c");
    	command.add("iptables -D INPUT -i " + intf + " -p tcp -m state --state NEW -m tcp --dport " + port + " -j DROP;" +
			        "iptables -D INPUT -i " + intf + " -p tcp -m state --state NEW -m tcp --dport " + port + " -j HTTP;" +
			        "iptables -D INPUT -i " + intf + " -p tcp -m state --state NEW -m tcp --dport " + "443" + " -j DROP;" +
			        "iptables -D INPUT -i " + intf + " -p tcp -m state --state NEW -m tcp --dport " + "443" + " -j HTTP;" +
			        "iptables -F HTTP;" +
			        "iptables -X HTTP;" +
			        "iptables -N HTTP;" +
    			    "iptables -I INPUT -i " + intf + " -p tcp -m state --state NEW -m tcp --dport " + port + " -j DROP;" +
    			    "iptables -I INPUT -i " + intf + " -p tcp -m state --state NEW -m tcp --dport " + "443" + " -j DROP;" +
    			    "iptables -I INPUT -i " + intf + " -p tcp -m state --state NEW -m tcp --dport " + port + " -j HTTP;" +
    	            "iptables -I INPUT -i " + intf + " -p tcp -m state --state NEW -m tcp --dport " + "443" + " -j HTTP;");

    	result = command.execute();
    	if (result != null) {
    		s_logger.warn("Error in opening up httpd port err=" + result );
    		return;
    	}
    	
    	command = new Script("/bin/bash", s_logger);
		command.add("-c");
    	command.add("service httpd start ");
    	result = command.execute();
    	if (result != null) {
    		s_logger.warn("Error in starting httpd service err=" + result );
    		return;
    	}
    	command = new Script("mkdir", s_logger);
		command.add("-p");
    	command.add("/var/www/html/copy/template");
    	result = command.execute();
    	if (result != null) {
    		s_logger.warn("Error in creating directory =" + result );
    		return;
    	}
    	
    	command = new Script("/bin/bash", s_logger);
		command.add("-c");
    	command.add("ln -sf " + publicTemplateRepo + " /var/www/html/copy/template");
    	result = command.execute();
    	if (result != null) {
    		s_logger.warn("Error in linking  err=" + result );
    		return;
    	}
	}
}    
