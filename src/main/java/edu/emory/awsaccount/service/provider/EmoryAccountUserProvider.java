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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
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
    private int m_requestTimeoutIntervalInMillis = 10000;
    private final static String ADMINISTRATOR_ROLE = "RHEDcloudAdministratorRole";
    private final static String AUDITOR_ROLE = "RHEDcloudAuditorRole";
    private final static String CENTRAL_ADMINISTRATOR_ROLE = "RHEDcloudCentralAdministratorRole";

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
        setCentralAdminRoleDnTemplate(centralAdminRoleDnTemplate);
        
        String requestTimeoutInterval = getProperties()
			.getProperty("requestTimeoutIntervalInMillis", "10000");
		int requestTimeoutIntervalInMillis = Integer.parseInt(requestTimeoutInterval);
		setRequestTimeoutIntervalInMillis(requestTimeoutIntervalInMillis);
		logger.info(LOGTAG + "requestTimeoutIntervalInMillis is: " + 
			getRequestTimeoutIntervalInMillis());
        		
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

        // If the AccountId is not null, handle it.
        if (querySpec.getAccountId() != null) {
        	logger.info(LOGTAG + "The accountId is not null. " +
        		"Querying for the specific account: " + 
        		querySpec.getAccountId());
        	List<AccountUser> accountUserList = 
        		query(querySpec.getAccountId());
        	return accountUserList;
        }
        else {
        	String errMsg = "QuerySpec not supported. AccountId is null.";
        	logger.error(LOGTAG + errMsg);
        	throw new ProviderException(errMsg);
        }
    }
    
    private List<AccountUser> query(String accountId) throws ProviderException {
    	
    	String LOGTAG = "[EmoryAccountUserProvider.query(String accountId)] ";
    	
    	logger.info(LOGTAG + "Getting query objects from AppConfig...");
    	
    	HashMap<String, AccountUser> accountUserMap = new HashMap<String, AccountUser>();
    	
    	// Retrieve all role assignments for users in the
    	// RHEDcloudAdministrator role.
    	logger.info(LOGTAG + "Querying for admin role assignments...");
    	List<RoleAssignment> adminRoleAssignments = 
    		roleAssignmentQuery(getAdminRoleDn(accountId));
    	
    	ListIterator adminRoleAssignmentListIterator =
    		adminRoleAssignments.listIterator();
    	while(adminRoleAssignmentListIterator.hasNext()) {
    		RoleAssignment ra = (RoleAssignment)adminRoleAssignmentListIterator.next();
    		String userId = getUserIdFromRoleAssignment(ra);
    		DirectoryPerson dp = directoryPersonQuery(userId);
    		UserProfile up = userProfileQuery(userId);
    		AccountUser au = buildAccountUser(accountId, dp, up, ADMINISTRATOR_ROLE);
    		accountUserMap.put(userId, au);
    	}
    	
    	// Retrieve all role assignments for users in the
    	// RHEDcloudAuditor role.
    	logger.info(LOGTAG + "Querying for auditor role assignments...");
    	List<RoleAssignment> auditorRoleAssignments = 
    		roleAssignmentQuery(getAuditorRoleDn(accountId));
    	
    	ListIterator auditorRoleAssignmentListIterator =
        		auditorRoleAssignments.listIterator();
    	while(auditorRoleAssignmentListIterator.hasNext()) {
    		RoleAssignment ra = (RoleAssignment)auditorRoleAssignmentListIterator.next();
    		String userId = getUserIdFromRoleAssignment(ra);
    		DirectoryPerson dp = directoryPersonQuery(userId);
    		UserProfile up = userProfileQuery(userId);
    		AccountUser au = buildAccountUser(accountId, dp, up, AUDITOR_ROLE);
    		// If the AccountUser already exists in the map,
    		// add the auditor role to the list of roles
    		if (accountUserMap.get(userId) != null) {
    			AccountUser user = (AccountUser)accountUserMap.get(userId);
    			user.addRoleName(AUDITOR_ROLE);
    		}
    		else {
    			accountUserMap.put(userId, au);
    		}
    	}
    	
    	// Retrieve all role assignments for users in the
    	// RHEDcloudCentralAdministrator role.
    	logger.info(LOGTAG + "Querying for central admin role assignments...");
    	List<RoleAssignment> centralAdminRoleAssignments = roleAssignmentQuery(getCentralAdminRoleDn(accountId));
		    	
    	ListIterator centralAdminRoleAssignmentListIterator =
        		centralAdminRoleAssignments.listIterator();
    	while(centralAdminRoleAssignmentListIterator.hasNext()) {
    		RoleAssignment ra = (RoleAssignment)centralAdminRoleAssignmentListIterator.next();
    		String userId = getUserIdFromRoleAssignment(ra);
    		
    		DirectoryPerson dp = null;
    		try {
    			dp = directoryPersonQuery(userId);
    		}
    		catch (ProviderException pe) {
    			String errMsg = "NO_DIRECTORY_PERSON:"
    				+ " An error occurred retrieving DirectoryPerson " +
    				"to build AccountUser. The exception is: " + pe.getMessage();
    			logger.error(LOGTAG + errMsg + ". Skipping user and continuing.");
    			continue;
    		}
    		
    		UserProfile up = null;
    		try {
    			up = userProfileQuery(userId);
    			
    		}
    		catch (ProviderException pe) {
    			String errMsg = "NO_USER_PROFILE: " +
    				" An error occurred retrieving UserProfile " +
    				"to build AccountUser. The exception is: " + pe.getMessage();
    			logger.error(LOGTAG + errMsg + ". Skipping user and continuing.");
    			continue;
    		}
    		
    		// Build the AccountUser from the DirectoryPerson and the UserProfile
    		AccountUser au = buildAccountUser(accountId, dp, up, CENTRAL_ADMINISTRATOR_ROLE);
    		
    		// If the AccountUser already exists in the map,
    		// add the auditor role to the list of roles
    		if (accountUserMap.get(userId) != null) {
    			AccountUser user = (AccountUser)accountUserMap.get(userId);
    			user.addRoleName(CENTRAL_ADMINISTRATOR_ROLE);
    		}
    		else {
    			accountUserMap.put(userId, au);
    		}
    	}
		
    	// Build the account user list from the map
    	ArrayList<AccountUser> accountUserList = new ArrayList<AccountUser>();
    	Set userIds = accountUserMap.keySet();
    	Iterator userIdIterator = userIds.iterator();
    	while (userIdIterator.hasNext()) {
    		String userId = (String)userIdIterator.next();
			AccountUser au = (AccountUser)accountUserMap.get(userId);
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
	
	private AccountUser getAccountUserFromList(List<AccountUser> accountUserList, String userId) {
		AccountUser accountUser = null;
		ListIterator li = accountUserList.listIterator();
		while (li.hasNext()) {
			AccountUser au = (AccountUser)li.next();
			if (au.getUserId().equals(userId)) accountUser = au;
		}
		
		return accountUser;
	}
	
	private boolean isAccountUserInList(List<AccountUser> accountUserList, String userId) {
		AccountUser accountUser = getAccountUserFromList(accountUserList, userId);
		if (accountUser != null) {
			return true;
		}
		else return false;
	}
	
	private List<RoleAssignment> roleAssignmentQuery(String roleDn) 
		throws ProviderException {
		
		String LOGTAG = "[EmoryAccountUserProvider.roleAssignmentQuery] ";
		
    	// Query the IDM service for all users in the named role
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
			querySpec.setRoleDN(roleDn);
			querySpec.setIdentityType("USER");
			querySpec.setDirectAssignOnly("true");
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
			PointToPointProducer p2p = 
				(PointToPointProducer)getIdmServiceProducerPool()
				.getExclusiveProducer();
			p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
			rs = (RequestService)p2p;
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the RoleAssignments for the Administrator Role.
		List roleAssignments = null;
		try {
			long startTime = System.currentTimeMillis();
			roleAssignments = roleAssignment.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for RoleAssignments for " +
				"roleDn " + roleDn + " in " + time + " ms. Returned " + 
				roleAssignments.size() + " users in the role.");
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
		
		return roleAssignments;
	}
	
	private DirectoryPerson directoryPersonQuery(String userId) 
		throws ProviderException {
		
    	// Query the DirectoryService service for the user's
	    // DirectoryPerson object.
	
    	// Get a configured DirectoryPerson and
	    // DirectoryPersonQuerySpecification from AppConfig
		DirectoryPerson directoryPerson = new DirectoryPerson();
    	DirectoryPersonQuerySpecification querySpec = new DirectoryPersonQuerySpecification();
		try {
			directoryPerson = (DirectoryPerson)m_appConfig
				.getObjectByType(directoryPerson.getClass().getName());
			querySpec = (DirectoryPersonQuerySpecification)m_appConfig
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
			querySpec.setKey(userId);
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
			PointToPointProducer p2p = 
				(PointToPointProducer)getDirectoryServiceProducerPool()
				.getExclusiveProducer();
			p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
			rs = (RequestService)p2p;		
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the DirectoryPerson.
		List directoryPersonList = null;
		try {
			long startTime = System.currentTimeMillis();
			directoryPersonList = directoryPerson.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for DirectoryPerson for " +
				"userId " + userId + " in " + time + " ms. Returned " + 
				directoryPersonList.size() + " user(s) in the role.");
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
			getDirectoryServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		if (directoryPersonList.size() == 0) {
			String errMsg = "Inappropriate number of DirectoryPerson " +
				"results. Expected 1 got " + directoryPersonList.size() +
				".";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		DirectoryPerson dp = (DirectoryPerson)directoryPersonList.get(0);
		return dp;
	}	
	
	private UserProfile userProfileQuery(String userId) 
		throws ProviderException {
		
    	// Query the AwsAccountService service for the user's
	    // UserProfile object.
	
    	// Get a configured UserProfile and
	    // UserProfileQuerySpecification from AppConfig
		UserProfile userProfile = new UserProfile();
    	UserProfileQuerySpecification querySpec = new UserProfileQuerySpecification();
		try {
			userProfile = (UserProfile)m_appConfig
				.getObjectByType(userProfile.getClass().getName());
			querySpec = (UserProfileQuerySpecification)m_appConfig
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
			querySpec.setUserId(userId);
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
			PointToPointProducer p2p = 
				(PointToPointProducer)getAwsAccountServiceProducerPool()
				.getExclusiveProducer();
			p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
			rs = (RequestService)p2p;	
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the UserProfile.
		List userProfileList = null;
		try {
			long startTime = System.currentTimeMillis();
			userProfileList = userProfile.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for the UserProfile for " +
				"userId " + userId + " in " + time + " ms. Returned " + 
				userProfileList.size() + " user profile(s).");
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
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		if (userProfileList.size() == 0) {
			return null;
		}
		else {
			UserProfile up = (UserProfile)userProfileList.get(0);
			return up;
		}
	}	

	private String getUserIdFromRoleAssignment(RoleAssignment ra) {
		String userDn = ra.getExplicitIdentityDNs().getDistinguishedName(0);
		String userId = parseUserId(userDn);
		return userId;
	}
	
	private AccountUser buildAccountUser (String accountId, DirectoryPerson dp,
		UserProfile up, String roleName) throws ProviderException {
		
		// Get a configured AccountUser from AppConfig
		AccountUser au = new AccountUser();
		try {
			au = (AccountUser)m_appConfig
				.getObjectByType(au.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		try {
			au.setAccountId(accountId);
			au.setUserId(dp.getKey());
			au.setFullName(dp.getFullName());
			
			EmailAddress emailAddress = au.newEmailAddress();
			emailAddress.setType(dp.getEmail().getType());
			emailAddress.setEmail(dp.getEmail().getEmailAddress());
			au.setEmailAddress(emailAddress);
			
			au.addRoleName(roleName);
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting field values of " +
				"an object. The exception is: " + efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
		
		return au;
		
	}
	
	private void setRequestTimeoutIntervalInMillis(int time) {
		m_requestTimeoutIntervalInMillis = time;
	}
	
	private int getRequestTimeoutIntervalInMillis() {
		return m_requestTimeoutIntervalInMillis;
	}
	
}
