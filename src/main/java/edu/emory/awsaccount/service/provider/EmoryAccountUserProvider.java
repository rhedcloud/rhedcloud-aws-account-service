/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.StringTokenizer;

import javax.jms.JMSException;

// Log4j
import org.apache.log4j.Category;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.user.v1_0.AccountUser;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserProfile;
import com.amazon.aws.moa.objects.resources.v1_0.AccountUserQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.EmailAddress;
import com.amazon.aws.moa.objects.resources.v1_0.UserProfileQuerySpecification;

import edu.emory.moa.jmsobjects.identity.v1_0.DirectoryPerson;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.DirectoryPersonQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;

/**
 * A provider of Emory AccountUsers that queries the IdmService, 
 * DirectoryService, and AwsAccountService.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 22 July 2018
 */
public class EmoryAccountUserProvider extends OpenEaiObject 
    implements AccountUserProvider {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;
    private ProducerPool m_awsAccountServiceProducerPool = null;
    private ProducerPool m_idmServiceProducerPool = null;
    private ProducerPool m_directoryServiceProducerPool = null;
    private String m_adminRoleDnTemplate = null;
    private String m_auditorRoleDnTemplate = null;
    private String m_centralAdminRoleDnTemplate = null;

    private String LOGTAG = "[EmoryAccountUserProvider] ";
   
	/**
	 * @see UserNotificationProvider.java
	 */
	@Override
	public void init(AppConfig aConfig) throws ProviderException {
		logger.info(LOGTAG + "Initializing...");
		m_appConfig = aConfig;

		// Get the provider properties
		PropertyConfig pConfig = new PropertyConfig();
		try {
			pConfig = (PropertyConfig)aConfig
					.getObject("AccountUserProviderProperties");
			setProperties(pConfig.getProperties());
		} 
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "Error retrieving a PropertyConfig object from "
					+ "AppConfig: The exception is: " + eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		
		String adminRoleDnTemplate = getProperties().getProperty("adminRoleDnTemplate");
        if (adminRoleDnTemplate == null || adminRoleDnTemplate.equals("")) {
        	String errMsg = "No base adminRoleDnTemplate property specified " +
        		"with which to build queries. Can't continue.";
        	throw new ProviderException(errMsg);
        }
        setAdminRoleDnTemplate(adminRoleDnTemplate);
        
        String auditorRoleDnTemplate = getProperties().getProperty("auditorRoleDnTemplate");
        if (auditorRoleDnTemplate == null || auditorRoleDnTemplate.equals("")) {
        	String errMsg = "No base auditorRoleDnTemplate property specified " +
        		"with which to build queries. Can't continue.";
        	throw new ProviderException(errMsg);
        }
        setAuditorRoleDnTemplate(auditorRoleDnTemplate);
        
        String centralAdminRoleDnTemplate = getProperties().getProperty("centralAdminRoleDnTemplate");
        if (centralAdminRoleDnTemplate == null || centralAdminRoleDnTemplate.equals("")) {
        	String errMsg = "No base centralAdminRoleDnTemplate property specified " +
        		"with which to build queries. Can't continue.";
        	throw new ProviderException(errMsg);
        }
        setCentralAdminRoleDnTemplate(adminRoleDnTemplate);
        		
		// This provider needs to send messages to the AWS account service
		// to query for UserProfiles.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("AwsAccountServiceProducerPool");
			setAwsAccountServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}	
		
		// This provider needs to send messages to the IDM service
		// to query for RoleAssignments.
		ProducerPool p2p2 = null;
		try {
			p2p2 = (ProducerPool)getAppConfig()
				.getObject("IdmServiceProducerPool");
			setIdmServiceProducerPool(p2p2);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		// This provider needs to send messages to the DirectoryService
		// to query for the DirectoryPerson object.
		ProducerPool p2p3 = null;
		try {
			p2p3 = (ProducerPool)getAppConfig()
				.getObject("DirectoryServiceProducerPool");
			setDirectoryServiceProducerPool(p2p3);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}	
		
		logger.info(LOGTAG + pConfig.getProperties().toString());

		logger.info(LOGTAG + "Initialization complete.");
	}
    
    /**
     * @throws ProviderException
     * @see AccountAliasProvider.java
     * 
     * Note: this implementation queries by AccountId and an empty query spec
     * to return all users of all accounts.
     */
    @Override
    public List<AccountUser> query(AccountUserQuerySpecification querySpec)
    	throws ProviderException {
    	
    	String LOGTAG = "[EmoryAccountUserProvider.query(AccountUserQuerySpecification querySpec)] ";

        // If the AccountId in the querySpec is null, query for all accounts.
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            logger.info(LOGTAG + "The AccountId is null. Querying for all " +
            	"users of all accounts.");
            List<AccountUser> accountUserList = query("1");
            return accountUserList;
        }
        // Otherwise, query for a specific account.
        else {
        	logger.info(LOGTAG + "The accountId is not null. Querying for the specific account: " + querySpec.getAccountId());
        	List<AccountUser> accountUserList = query(querySpec.getAccountId());
        	return accountUserList;
        }
    }
    
    private List<AccountUser> query(String accountId) throws ProviderException {
    	
    	String LOGTAG = "[EmoryAccountUserProvider.query(String accountId)] ";
    	
    	logger.info(LOGTAG + "Getting query objects from AppConfig...");
    	
    	// Query the IDM service for all users in the RHEDcloudAdministrator role
    	// Get a configured AccountUser, RoleAssignment, and 
    	// RoleAssignmentQuerySpecification from AppConfig
    	AccountUser accountUser = new AccountUser();
		RoleAssignment roleAssignment = new RoleAssignment();
    	RoleAssignmentQuerySpecification querySpec = new RoleAssignmentQuerySpecification();
		try {
			accountUser = (AccountUser)m_appConfig
					.getObjectByType(accountUser.getClass().getName());
			roleAssignment = (RoleAssignment)m_appConfig
				.getObjectByType(roleAssignment.getClass().getName());
			querySpec = (RoleAssignmentQuerySpecification)m_appConfig
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the querySpec.
		try {
			querySpec.setRoleDN(getAdminRoleDn(accountId));
			querySpec.setIdentityType("USER");
			querySpec.setDirectAssignOnly("true");
			try {
				logger.info(LOGTAG + "Query spec is: " + querySpec.toXmlString());
			}
			catch (XmlEnterpriseObjectException xeoe) {
				logger.error(xeoe.getMessage());
			}
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
    	
    	// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getIdmServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the RoleAssignments for the Administrator Role.
		List adminRoleAssignments = null;
		try {
			long startTime = System.currentTimeMillis();
			adminRoleAssignments = roleAssignment.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for Administrator RoleAssignment " +
				"objects in " + time + " ms. Returned " + 
				adminRoleAssignments.size() + " users in the Administrator role.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred queryign for the " +
					"RoleAssignment objects The exception is: " + 
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getIdmServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		    	
    	// Query the IDM service for all users in the RHEDcloudAuditor role
		// Set the values of the querySpec.
		try {
			querySpec.setRoleDN(getAuditorRoleDn(accountId));
			try {
				logger.info(LOGTAG + "Query spec is: " + querySpec.toXmlString());
			}
			catch (XmlEnterpriseObjectException xeoe) {
				logger.error(xeoe.getMessage());
			}
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
    	
    	// Get a RequestService to use for this transaction.
		try {
			rs = (RequestService)getIdmServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the RoleAssignments.
		List auditorRoleAssignments = null;
		try {
			long startTime = System.currentTimeMillis();
			auditorRoleAssignments = roleAssignment.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for Auditor RoleAssignment " +
				"objects in " + time + " ms. Returned " + 
				auditorRoleAssignments.size() + " users in the Auditor role.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"RoleAssignment objects The exception is: " + 
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getIdmServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
    	
    	// Query the IDM service for all users in the AwsCentralAdministrators role
		try {
			querySpec.setRoleDN(getAuditorRoleDn(accountId));
			try {
				logger.info(LOGTAG + "Query spec is: " + querySpec.toXmlString());
			}
			catch (XmlEnterpriseObjectException xeoe) {
				logger.error(xeoe.getMessage());
			}
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
    	
    	// Get a RequestService to use for this transaction.
		try {
			rs = (RequestService)getIdmServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the RoleAssignments.
		List centralAdminRoleAssignments = null;
		try {
			long startTime = System.currentTimeMillis();
			centralAdminRoleAssignments = roleAssignment.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for CentralAdministrator " +
				"RoleAssignment objects in " + time + " ms. Returned " + 
				centralAdminRoleAssignments.size() + 
				" users in the CentralAdministrator role.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"RoleAssignment objects The exception is: " + 
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getIdmServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		// Get a configured DirectoryPerson, DirectoryPersonQuerySpecification,
		// UserProfile, and UserProfileQuery specification from AppConfig
		DirectoryPerson directoryPerson = new DirectoryPerson();
		DirectoryPersonQuerySpecification dpqs = new DirectoryPersonQuerySpecification();
		UserProfile userProfile = new UserProfile();
		UserProfileQuerySpecification upqs = new UserProfileQuerySpecification();
		try {
			directoryPerson = (DirectoryPerson)m_appConfig
					.getObjectByType(directoryPerson.getClass().getName());
			dpqs = (DirectoryPersonQuerySpecification)m_appConfig
				.getObjectByType(dpqs.getClass().getName());
			userProfile = (UserProfile)m_appConfig
				.getObjectByType(userProfile.getClass().getName());
			upqs = (UserProfileQuerySpecification)m_appConfig
					.getObjectByType(upqs.getClass().getName()); 
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// For each user, parse the PPID out of their DN and query the
		// DirectoryService for DirectoryPerson and the AWS Account Service
		// for the UserProfile.
		List<AccountUser> accountUserList = new ArrayList<AccountUser>();
		ListIterator adminListIterator = adminRoleAssignments.listIterator();
		while (adminListIterator.hasNext()) {
			RoleAssignment ra = (RoleAssignment)adminListIterator.next();
			String userId = parseUserId(ra.getIdentityDN());
			try {
				dpqs.setKey(userId);
				upqs.setUserId(userId);
			}
			catch (EnterpriseFieldException efe) {
				String errMsg = "An error occurred setting field values of " +
					"an object. The exception is: " + efe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, efe);
			}
			
			// Query the DirectoryService for DirectoryPerson
			// Get a RequestService to use for this transaction.
			try {
				rs = (RequestService)getDirectoryServiceProducerPool().getExclusiveProducer();
			}
			catch (JMSException jmse) {
				String errMsg = "An error occurred getting a request service to use " +
					"in this transaction. The exception is: " + jmse.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, jmse);
			}
			// Perform the query
			List directoryPersonList = null;
			try {
				long startTime = System.currentTimeMillis();
				directoryPersonList = directoryPerson.query(dpqs, rs);
				long time = System.currentTimeMillis() - startTime;
				logger.info(LOGTAG + "Queried for DirectoryPerson " +
					"objects in " + time + " ms.");
			}
			catch (EnterpriseObjectQueryException eoqe) {
				String errMsg = "An error occurred creating the " +
						"UserNotification object The exception is: " + 
						eoqe.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, eoqe);
			}
			// In any case, release the producer back to the pool.
			finally {
				getIdmServiceProducerPool().releaseProducer((PointToPointProducer)rs);
	    	}
			
			// Query the AWS Account Service for the UserProfile
			// Get a RequestService to use for this transaction.
			try {
				rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
			}
			catch (JMSException jmse) {
				String errMsg = "An error occurred getting a request service to use " +
					"in this transaction. The exception is: " + jmse.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, jmse);
			}
			// Perform the query
			List userProfileList = null;
			try {
				long startTime = System.currentTimeMillis();
				userProfileList = userProfile.query(upqs, rs);
				long time = System.currentTimeMillis() - startTime;
				logger.info(LOGTAG + "Queried for UserProfile " +
					"objects in " + time + " ms.");
			}
			catch (EnterpriseObjectQueryException eoqe) {
				String errMsg = "An error occurred creating the " +
						"UserNotification object The exception is: " + 
						eoqe.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, eoqe);
			}
			// In any case, release the producer back to the pool.
			finally {
				getIdmServiceProducerPool().releaseProducer((PointToPointProducer)rs);
	    	}
			
			// Get the DirectoryPerson
			DirectoryPerson dp = (DirectoryPerson)directoryPersonList.get(0);
			UserProfile up = (UserProfile)userProfileList.get(0);		
			
			// Build the AccountUser
			AccountUser au = null;
			try {
				au = (AccountUser)accountUser.clone();
			}
			catch (CloneNotSupportedException cnse) {
				String errMsg = "An error occurred cloning an object. " +
						"The exception is: " + cnse.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, cnse);
			}
			
			try {
				au.setAccountId(accountId);
				au.setUserId(userId);
				au.setFullName(dp.getFullName());
				
				EmailAddress emailAddress = au.newEmailAddress();
				emailAddress.setType(dp.getEmail().getType());
				emailAddress.setEmail(dp.getEmail().getEmailAddress());
				au.setEmailAddress(emailAddress);
				
				au.addRoleName("RHEDcloudAdministrator");
			}
			catch (EnterpriseFieldException efe) {
				String errMsg = "An error occurred setting field values of " +
					"an object. The exception is: " + efe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, efe);
			}
			
			accountUserList.add(au);
		}
		
		
		
		
		return accountUserList;
	
    }
    
    private void setAppConfig(AppConfig aConfig) {
    	m_appConfig = aConfig;
    }
    
    private AppConfig getAppConfig() {
		return m_appConfig;
	}
	
	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}
	
	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}
	
	private void setIdmServiceProducerPool(ProducerPool pool) {
		m_idmServiceProducerPool = pool;
	}
	
	private ProducerPool getIdmServiceProducerPool() {
		return m_idmServiceProducerPool;
	}
	
	private void setDirectoryServiceProducerPool(ProducerPool pool) {
		m_directoryServiceProducerPool = pool;

	}
	
	private void getDirectoryServiceProducerPool(ProducerPool pool) {
		m_directoryServiceProducerPool = pool;
	}
	
	private ProducerPool getDirectoryServiceProducerPool() {
		return m_directoryServiceProducerPool;
	}
	
	private void setAdminRoleDnTemplate(String template) {
		m_adminRoleDnTemplate = template;
	}
	
	private String getAdminRoleDnTemplate() {
		return m_adminRoleDnTemplate;
	}
	
	private String getAdminRoleDn(String accountId) {
		String adminRoleDn = getAdminRoleDnTemplate()
			.replace("ACCOUNT_NUMBER", accountId);
		return adminRoleDn;
	}
	
	private void setAuditorRoleDnTemplate(String template) {
		m_auditorRoleDnTemplate = template;
	}
	
	private String getAuditorRoleDnTemplate() {
		return m_auditorRoleDnTemplate;
	}
	
	private String getAuditorRoleDn(String accountId) {
		String auditorRoleDn = getAuditorRoleDnTemplate()
			.replace("ACCOUNT_NUMBER", accountId);
		return auditorRoleDn;
	}
	
	private void setCentralAdminRoleDnTemplate(String template) {
		m_centralAdminRoleDnTemplate = template;
	}
	
	private String getCentralAdminRoleDnTemplate() {
		return m_centralAdminRoleDnTemplate;
	}
	
	private String getCentralAdminRoleDn(String accountId) {
		String centralAdminRoleDn = getCentralAdminRoleDnTemplate()
			.replace("ACCOUNT_NUMBER", accountId);
		return centralAdminRoleDn;
	}
	
	private String parseUserId(String dn) {
		StringTokenizer st1 = new StringTokenizer(dn, ",");
		String firstToken = st1.nextToken();
		StringTokenizer st2 = new StringTokenizer(firstToken, "=");
		st2.nextToken();
		String userId = st2.nextToken();
		return userId;
	}
	
}
