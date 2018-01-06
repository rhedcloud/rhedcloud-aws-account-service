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
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;

/**
 *  An example object provider that maintains an in-memory
 *  store of stacks.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class ExampleStackProvider extends OpenEaiObject 
implements StackProvider {

	private Category logger = OpenEaiObject.logger;
	private AppConfig m_appConfig;
	private String m_provideReplyUri = null;
	private String m_responseReplyUri = null;
	private long m_stackId = 2646351098L;
	private HashMap<String, Stack> m_stackMap = new HashMap();
	private String LOGTAG = "[ExampleStackProvider] ";
	
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
					.getObject("StackProviderProperties");
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
	public List<Stack> query(StackQuerySpecification querySpec)
			throws ProviderException {

		// If the StackId is null, throw an exception.
		if (querySpec.getStackId() == null || querySpec.getStackId().equals("")) {
			String errMsg = "The StackId is null. The ExampleStackProvider" +
				"presently only implements query by StackId.";
			throw new ProviderException(errMsg);
		}
		
		// If there is no match, return null.
		if (m_stackMap.get(querySpec.getStackId()) == null) return null;
		
		
		// Otherwise return the Stack from the VPC map
		else {
			List<Stack> stackList = new ArrayList();
			stackList.add((Stack)m_stackMap.get(querySpec.getStackId()));
			return stackList;
		}
	}

	/**
	 * @see StackProvider.java
	 */
	public Stack generate(StackRequisition req)
			throws ProviderException {

		// Get a configured Stack object from AppConfig
		Stack stack = new Stack();
		try {
			stack = (Stack)m_appConfig.getObjectByType(stack
					.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}

		// Set the values of the Stack.	
		try {
			stack.setStackId(Long.toHexString(++m_stackId));
			stack.setStackName(req.getStackName());
			stack.setDescription(req.getDescription());
			stack.setStackParameter(req.getStackParameter());
			stack.setTag(req.getTag());
			stack.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
			stack.setCapability(req.getCapability());
			if (req.getDisableRollback() != null) {
				stack.setDisableRollback(req.getDisableRollback());
			}
			else {
				stack.setDisableRollback("false");
			}
			
			Output out1 = stack.newOutput();
			out1.setDescription("SubnetId of the VPN connected subnet");
			out1.setOutputKey("PrivateSubnet");
			out1.setOutputValue("subnet-8fa499d7");
			
			Output out2 = stack.newOutput();
			out2.setDescription("VPCId of the newly created VPC");
			out2.setOutputKey("VPCId");
			out2.setOutputValue("vpc-82d54be5");
			
			List<Output> outList = new ArrayList<Output>();
			outList.add(out1);
			outList.add(out2);
			stack.setOutput(outList);
			
			stack.setStackStatus("CREATE_COMPLETE");
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"Stack object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
		
		// Add the Stack to the map.
		m_stackMap.put(stack.getStackId(), stack);

		// Return the object.
		return stack;
	}

	/**
	 * @see StackProvider.java
	 */
	public void update(Stack stack) throws ProviderException {		
		
		// Replace the object in the map with the same StackId.
		m_stackMap.put(stack.getStackId(), stack);

		return;
	}
	
	/**
	 * @see StackProvider.java
	 */
	public void delete(Stack stack) throws ProviderException {		
		
		// Remove the object in the map with the same StackId.
		m_stackMap.remove(stack.getStackId());

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
