/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Java utilities
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

// Log4j
import org.apache.log4j.Category;

// JDOM
import org.jdom.Document;
import org.jdom.Element;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

//AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.user.v1_0.AccountUser;
import com.amazon.aws.moa.objects.resources.v1_0.AccountUserQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.EmailAddress;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;

/**
 *  An example object provider that maintains returns AccountUsers.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class ExampleAccountUserProvider extends OpenEaiObject 
implements AccountUserProvider {

	private Category logger = OpenEaiObject.logger;
	private AppConfig m_appConfig;
	private String LOGTAG = "[ExampleAccountUserProvider] ";
	
	/**
	 * @see AccountUserProvider.java
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
		} 
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "Error retrieving a PropertyConfig object from "
					+ "AppConfig: The exception is: " + eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		
		logger.info(LOGTAG + pConfig.getProperties().toString());

		logger.info(LOGTAG + "Initialization complete.");
	}

	/**
	 * @see AccountUserProvider.java
	 * 
	 * Note: this implementation queries by AccountId.
	 */
	public List<AccountUser> query(AccountUserQuerySpecification querySpec)
			throws ProviderException {

		// If the AccountId is null, throw an exception.
		if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
			String errMsg = "The AccountId is null. The ExampleAccountUserProvider" +
				"presently only implements query by AccountId.";
			throw new ProviderException(errMsg);
		}
		
		// Get a new AccountUser from AppConfig
		AccountUser au = new AccountUser();
		try {
            au = (AccountUser) m_appConfig.getObjectByType(au.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig. " +
            	"The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, eoce);
        }
		
		// Set the values of AccountUser
		try {
			au.setAccountId(querySpec.getAccountId());
			au.setUserId("P999999");
			au.setFullName("Ziggy Stardust");
			
			EmailAddress emailAddress = au.newEmailAddress();
			emailAddress.setType("primary");
			emailAddress.setEmail("ziggy@stardust.net");
			au.setEmailAddress(emailAddress);
			
			au.addRoleName("RHEDcloudAdministrator");
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the field values " +
				"of AccountUser. The exception is: " + efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
		
		List<AccountUser> accountUserList = new ArrayList();
		accountUserList.add(au);
		return accountUserList;
		
	}

}