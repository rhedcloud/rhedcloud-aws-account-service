package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayConnectionProfile;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayConnectionProfileAssignmentRequisition;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayConnectionProfileQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * Step to determine the (next) CIDR range for the Transit Gateway attachment.
 * <br>
 * Send a TransitGatewayConnectionProfileAssignment.Generate-Request to the
 * NetworkOpsService to reserve a TransitGatewayConnectionProfile for this provisioning run.
 */
public class DetermineVpcTgwCidr extends AbstractStep implements Step {
	private static final int REQUEST_TIMEOUT_INTERVAL_DEFAULT = 30_000;

	private ProducerPool networkOpsServiceProducerPool = null;
	private int requestTimeoutInterval = REQUEST_TIMEOUT_INTERVAL_DEFAULT;

	public void init (String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) throws StepException {
		super.init(provisioningId, props, aConfig, vpcpp);

		String LOGTAG = getStepTag() + "[DetermineVpcTgwCidr.init] ";

		try {
			networkOpsServiceProducerPool = (ProducerPool) getAppConfig().getObject("NetworkOpsServiceProducerPool");
		}
		catch (EnterpriseConfigurationObjectException e) {
			String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}

		// requestTimeoutInterval is the time to wait for the response to the request
		String timeout = getProperties().getProperty("requestTimeoutInterval", String.valueOf(REQUEST_TIMEOUT_INTERVAL_DEFAULT));
		requestTimeoutInterval = Integer.parseInt(timeout);
		logger.info(LOGTAG + "requestTimeoutInterval is: " + requestTimeoutInterval);
	}

	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DetermineVpcTgwCidr.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

		// previous steps set more inputs
		boolean createVpc = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_VPC_TYPE", "createVpc"));
		String vpcConnectionMethod = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "vpcConnectionMethod");

		if (!createVpc || !vpcConnectionMethod.equals("TGW")) {
			if (!createVpc)
				logger.info(LOGTAG + "Bypassing TGW CIDR determination since no VPC is being created");
			else
				logger.info(LOGTAG + "Bypassing TGW CIDR determination since VPC does not have TGW connectivity");

			addResultProperty("vpcNetwork", "not applicable");

			// Update the step.
			update(COMPLETED_STATUS, SUCCESS_RESULT);

			// Log completion time.
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Step run completed in " + time + " ms.");

			// Return the properties.
			return getResultProperties();
		}

		// gather inputs from the provisioning requisition and previous steps
		String provisioningId = getVirtualPrivateCloudProvisioning().getProvisioningId();
		String region = getVirtualPrivateCloudProvisioning().getVirtualPrivateCloudRequisition().getRegion();
		String transitGatewayId = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "transitGatewayId");
		logger.info(LOGTAG + "Using ProvisioningId " + provisioningId
				+ " to reserve TGW connection profile for " + transitGatewayId + " in region " + region);

		addResultProperty("region", region);

		TransitGatewayConnectionProfile profile;
		TransitGatewayConnectionProfileQuerySpecification profileQuerySpec;
		TransitGatewayConnectionProfileAssignment assignment;
		TransitGatewayConnectionProfileAssignmentRequisition requisition;
		try {
			profile = (TransitGatewayConnectionProfile) getAppConfig().getObjectByType(TransitGatewayConnectionProfile.class.getName());
			profileQuerySpec = (TransitGatewayConnectionProfileQuerySpecification) getAppConfig().getObjectByType(TransitGatewayConnectionProfileQuerySpecification.class.getName());
			assignment = (TransitGatewayConnectionProfileAssignment) getAppConfig().getObjectByType(TransitGatewayConnectionProfileAssignment.class.getName());
			requisition = (TransitGatewayConnectionProfileAssignmentRequisition) getAppConfig().getObjectByType(TransitGatewayConnectionProfileAssignmentRequisition.class.getName());
		}
		catch (EnterpriseConfigurationObjectException e) {
			String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}

		try {
			requisition.setRegion(region);
			requisition.setOwnerId(provisioningId);
			requisition.setTransitGatewayId(transitGatewayId);

			logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignmentRequisition is: " + requisition.toXmlString());
		}
		catch (EnterpriseFieldException e) {
			String errMsg = "An error occurred setting the values of the TransitGatewayConnectionProfileAssignmentRequisition. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}
		catch (XmlEnterpriseObjectException e) {
			String errMsg = "An error occurred serializing the TransitGatewayConnectionProfileAssignmentRequisition to XML. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}

		// Get a producer from the pool
		PointToPointProducer p2p;
		try {
			p2p = (PointToPointProducer) networkOpsServiceProducerPool.getExclusiveProducer();
			p2p.setRequestTimeoutInterval(requestTimeoutInterval);
		}
		catch (JMSException e) {
			String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}

		try {
			long elapsedStartTime = System.currentTimeMillis();
			@SuppressWarnings("unchecked")
			List<TransitGatewayConnectionProfileAssignment> assignments = assignment.generate(requisition, p2p);
			long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
			logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignment.Generate took " + elapsedTime + " ms. Returned " + assignments.size() + " results.");

			if (assignments.size() != 1) {
				String errMsg = "Invalid number of results returned from TransitGatewayConnectionProfileAssignment.Generate. " +
						assignments.size() + " results returned. Expected exactly 1.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}

			profileQuerySpec.setTransitGatewayConnectionProfileId(assignments.get(0).getTransitGatewayConnectionProfileId());
			logger.info(LOGTAG + "TransitGatewayConnectionProfileQuerySpecification is: " + profileQuerySpec.toXmlString());

			elapsedStartTime = System.currentTimeMillis();
			@SuppressWarnings("unchecked")
			List<TransitGatewayConnectionProfile> profiles = profile.query(profileQuerySpec, p2p);
			elapsedTime = System.currentTimeMillis() - elapsedStartTime;
			logger.info(LOGTAG + "TransitGatewayConnectionProfile.Query took " + elapsedTime + " ms. Returned " + profiles.size() + " results.");

			if (profiles.size() != 1) {
				String errMsg = "Invalid number of results returned from TransitGatewayConnectionProfile.Query. " +
						profiles.size() + " results returned. Expected exactly 1.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}

			// the prize
			TransitGatewayConnectionProfile assignedConnectionProfile = profiles.get(0);

			addResultProperty("transitGatewayConnectionProfileId", assignedConnectionProfile.getTransitGatewayConnectionProfileId());
			addResultProperty("cidrId", assignedConnectionProfile.getCidrId());
			addResultProperty("vpcNetwork", assignedConnectionProfile.getCidrRange());  // used by future steps
		}
		catch (EnterpriseObjectGenerateException e) {
			String errMsg = "An error occurred for TransitGatewayConnectionProfileAssignment.Generate. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}
		catch (EnterpriseObjectQueryException e) {
			String errMsg = "An error occurred for TransitGatewayConnectionProfile.Query. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}
		catch (EnterpriseFieldException e) {
			String errMsg = "An error occurred setting the values of the TransitGatewayConnectionProfileQuerySpecification. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}
		catch (XmlEnterpriseObjectException e) {
			String errMsg = "An error occurred serializing the TransitGatewayConnectionProfileQuerySpecification to XML. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}
		finally {
			networkOpsServiceProducerPool.releaseProducer(p2p);
		}

		// Update the step.
		update(COMPLETED_STATUS, SUCCESS_RESULT);

    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + " ms.");

    	// Return the properties.
    	return getResultProperties();
	}

	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DetermineVpcTgwCidr.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");

		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);

		// Simulated result properties.
		addResultProperty("vpcNetwork", "10.98.4.0/23");  // used by future steps

		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT);

    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " + time + " ms.");

    	// Return the properties.
    	return getResultProperties();
	}

	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DetermineVpcTgwCidr.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");

		// Set return properties.
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);

    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + " ms.");

    	// Return the properties.
    	return getResultProperties();
	}

	public void rollback() throws StepException {
		long startTime = System.currentTimeMillis();

		super.rollback();

		String LOGTAG = getStepTag() + "[DetermineVpcTgwCidr.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

		// Update the step.
		update(ROLLBACK_STATUS, SUCCESS_RESULT);

		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + " ms.");
	}
}
