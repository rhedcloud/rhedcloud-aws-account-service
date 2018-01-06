/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
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

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.SamlProvider;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.SamlProviderQuerySpecification;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.CreateSAMLProviderRequest;
import com.amazonaws.services.identitymanagement.model.CreateSAMLProviderResult;
import com.amazonaws.services.identitymanagement.model.DeleteSAMLProviderRequest;
import com.amazonaws.services.identitymanagement.model.DeleteSAMLProviderResult;
import com.amazonaws.services.identitymanagement.model.ListSAMLProvidersRequest;
import com.amazonaws.services.identitymanagement.model.ListSAMLProvidersResult;
import com.amazonaws.services.identitymanagement.model.UpdateSAMLProviderRequest;
import com.amazonaws.services.identitymanagement.model.UpdateSAMLProviderResult;


/**
 *  An example object provider that maintains an in-memory
 *  store of stacks.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class AwsSamlProviderProvider extends OpenEaiObject 
implements SamlProviderProvider {

	private Category logger = OpenEaiObject.logger;
	private AppConfig m_appConfig;
	private String m_provideReplyUri = null;
	private String m_responseReplyUri = null;
	private long m_stackId = 2646351098L;
	private HashMap<String, SamlProvider> m_stackMap = new HashMap();
	private String LOGTAG = "[AwsSamlProviderProvider] ";
	private AmazonIdentityManagement amazonIdentityManagement=AmazonIdentityManagementClientBuilder.defaultClient();
	/**
	 * @see VirtualPrivateCloudProvider.java
	 */
	@Override
	public void init(AppConfig aConfig) throws ProviderException {
		logger.info(LOGTAG + "Initializing...");
		m_appConfig = aConfig;

		// Get the provider properties
		PropertyConfig pConfig = new PropertyConfig();
		try {
			pConfig = (PropertyConfig)aConfig
					.getObject("AwsSamlProviderProviderProperties");
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
	 * @see StackProvider.java
	 * 
	 * Note: this implementation queries by StackId.
	 */
	public List<SamlProvider> query(SamlProviderQuerySpecification querySpec)
			throws ProviderException {

		// If the StackId is null, throw an exception.
		if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
			String errMsg = "The StackId is null. The ExampleStackProvider" +
				"presently only implements query by StackId.";
			throw new ProviderException(errMsg);
		}
		
        ListSAMLProvidersRequest request=new ListSAMLProvidersRequest();
        //TODO: querySpec to request
        ListSAMLProvidersResult result=amazonIdentityManagement.listSAMLProviders(request);
        // Replace the object in the map with the same StackId.
        //TODO: check result

		return null;
	}

	/**
	 * @see StackProvider.java
	 */
	public void create(SamlProvider req)
			throws ProviderException {

		// Get a configured Stack object from AppConfig
	    SamlProvider samlProvider = new SamlProvider();
		try {
			samlProvider = (SamlProvider)m_appConfig.getObjectByType(samlProvider
					.getClass().getName());
			//TODO: map req to request
			CreateSAMLProviderRequest request=new CreateSAMLProviderRequest();
			CreateSAMLProviderResult result=amazonIdentityManagement.createSAMLProvider(request);
			//TODO: check result
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}

		// Add the Stack to the map.
		m_stackMap.put(req.getAccountId(), req);
	}

	/**
	 * @see StackProvider.java
	 */
	public void update(SamlProvider samlProvider) throws ProviderException {		
	    UpdateSAMLProviderRequest request=new UpdateSAMLProviderRequest();
	    //TODO: samlProvier to request
	    UpdateSAMLProviderResult result=amazonIdentityManagement.updateSAMLProvider(request);
		// Replace the object in the map with the same StackId.
		//TODO: check result
		return;
	}
	
	/**
	 * @see StackProvider.java
	 */
	public void delete(SamlProvider stack) throws ProviderException {		
		
        DeleteSAMLProviderRequest request=new DeleteSAMLProviderRequest();
        //TODO: samlProvier to request
        DeleteSAMLProviderResult result=amazonIdentityManagement.deleteSAMLProvider(request);
        // Replace the object in the map with the same StackId.
        //TODO: check result));

		return;
	}

	/**
	 * @param String, the URI to a provide reply document containing a
	 * sample object.
	 * <P>
	 * This method sets the provide reply URI property
	 */
	private void setProvideReplyUri(String provideReplyUri) {
		m_provideReplyUri = provideReplyUri;
	}

	/**
	 * @return String, the provide reply document containing a
	 * sample object
	 * <P>
	 * This method returns the value of the provide reply URI property
	 */
	private String getProvideReplyUri() {
		return m_provideReplyUri;
	}

	/**
	 * @param String, the URI to a response reply document containing a
	 * sample object.
	 * <P>
	 * This method sets the provide reply URI property
	 */
	private void setResponseReplyUri(String responseReplyUri) {
		m_responseReplyUri = responseReplyUri;
	}

	/**
	 * @return String, the response reply document containing a
	 * sample object
	 * <P>
	 * This method returns the value of the response reply URI property
	 */
	private String getResponseReplyUri() {
		return m_responseReplyUri;
	}
}
