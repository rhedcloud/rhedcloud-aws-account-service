package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGateway;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayProfile;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * Step to determine if a VPC should be Transit Gateway attached or use VPN connectivity.
 */
public class DetermineVpcConnectionMethod extends AbstractStep implements Step {
	private static final int REQUEST_TIMEOUT_INTERVAL_DEFAULT = 30_000;

	private ProducerPool networkOpsServiceProducerPool = null;
	private int requestTimeoutInterval = REQUEST_TIMEOUT_INTERVAL_DEFAULT;
	private String environment;

	public void init (String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) throws StepException {
		super.init(provisioningId, props, aConfig, vpcpp);

		String LOGTAG = getStepTag() + "[DetermineVpcConnectionMethod.init] ";

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

		// environment is used for transit gateway determination
		environment = getProperties().getProperty("environment");
		logger.info(LOGTAG + "environment is: " + environment);
	}

	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DetermineVpcConnectionMethod.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

		// region comes from the requisition
		String region = getVirtualPrivateCloudProvisioning().getVirtualPrivateCloudRequisition().getRegion();
		addResultProperty("region", region);
		addResultProperty("environment", environment);

		// previous steps set more inputs
		boolean allocateNewAccount = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_NEW_OR_EXISTING_ACCOUNT", "allocateNewAccount"));
		boolean createVpc = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_VPC_TYPE", "createVpc"));

		// for a new account, we'll determine the connection method only if a VPC is going to be created
		// for an existing account, we know a VPC is going to be created (validated by DetermineVpcType)
		boolean determineConnectionMethod;
		if (allocateNewAccount) {
			determineConnectionMethod = createVpc;
		}
		else {
			determineConnectionMethod = true;
		}

		if (determineConnectionMethod) {
			logger.info(LOGTAG + "Determining connection method");

			TransitGateway transitGateway;
			TransitGatewayQuerySpecification transitGatewayQuerySpec;
			try {
				transitGateway = (TransitGateway) getAppConfig().getObjectByType(TransitGateway.class.getName());
				transitGatewayQuerySpec = (TransitGatewayQuerySpecification) getAppConfig().getObjectByType(TransitGatewayQuerySpecification.class.getName());
			}
			catch (EnterpriseConfigurationObjectException e) {
				String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}

			try {
				transitGatewayQuerySpec.setEnvironment(environment);
				transitGatewayQuerySpec.setRegion(region);

				logger.info(LOGTAG + "TransitGatewayQuerySpecification is: " + transitGatewayQuerySpec.toXmlString());
			}
			catch (EnterpriseFieldException e) {
				String errMsg = "An error occurred setting the values of the TransitGatewayQuerySpecification. The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}
			catch (XmlEnterpriseObjectException e) {
				String errMsg = "An error occurred serializing the TransitGatewayQuerySpecification to XML. The exception is: " + e.getMessage();
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

			TransitGateway tgw = null;

			try {
				long elapsedStartTime = System.currentTimeMillis();
				@SuppressWarnings("unchecked")
				List<TransitGateway> gateways = transitGateway.query(transitGatewayQuerySpec, p2p);
				long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
				logger.info(LOGTAG + "TransitGateway.Query took " + elapsedTime + " ms. Returned " + gateways.size() + " results.");

				// While we don’t have more than one TGW per region per account, it is possible to have up to 5 TGWs per region per account
				// A TGW resides in only one region (for VPCs in that region)
				// so, if there are more than one gateway, for now just take the first one
				if (gateways.size() > 0) {
					tgw = gateways.get(0);
				}

				// Set result properties.
				if (tgw == null) {
					addResultProperty("vpcConnectionMethod", "VPN");
					addResultProperty("transitGatewayId", "not applicable");
					addResultProperty("transitGatewayAccountId", "not applicable");
					addResultProperty("transitGatewayAssociationRouteTableId_0", "not applicable");
					addResultProperty("transitGatewayPropagationRouteTableId_0_0", "not applicable");
				}
				else {
					addResultProperty("vpcConnectionMethod", "TGW");
					addResultProperty("transitGatewayId", tgw.getTransitGatewayId());
					addResultProperty("transitGatewayAccountId", tgw.getAccountId());
					for (int i = 0; i < tgw.getTransitGatewayProfile().size(); i++) {
						TransitGatewayProfile tgwProfile = (TransitGatewayProfile) tgw.getTransitGatewayProfile().get(i);
						addResultProperty("transitGatewayAssociationRouteTableId_" + i, tgwProfile.getAssociationRouteTableId());
						for (int j = 0; j < tgwProfile.getPropagationRouteTableId().size(); j++) {
							addResultProperty("transitGatewayPropagationRouteTableId_" + i + "_" + j, (String) tgwProfile.getPropagationRouteTableId().get(j));
						}
					}
				}
			}
			catch (EnterpriseObjectQueryException e) {
				String errMsg = "An error occurred for TransitGateway.Query. The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}
			finally {
				networkOpsServiceProducerPool.releaseProducer(p2p);
			}
		}
		else {
			logger.info(LOGTAG + "Not determining connection method");

			addResultProperty("vpcConnectionMethod", "not applicable");
			addResultProperty("transitGatewayId", "not applicable");
			addResultProperty("transitGatewayAccountId", "not applicable");
			addResultProperty("transitGatewayAssociationRouteTableId_0", "not applicable");
			addResultProperty("transitGatewayPropagationRouteTableId_0_0", "not applicable");
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
		String LOGTAG = getStepTag() + "[DetermineVpcConnectionMethod.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");

		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);

		// Simulated result properties.
		addResultProperty("vpcConnectionMethod", "VPN");
		addResultProperty("transitGatewayId", "not applicable");
		addResultProperty("transitGatewayAccountId", "not applicable");
		addResultProperty("transitGatewayAssociationRouteTableId_0", "not applicable");
		addResultProperty("transitGatewayPropagationRouteTableId_0_0", "not applicable");

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
		String LOGTAG = getStepTag() + "[DetermineVpcConnectionMethod.fail] ";
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

		String LOGTAG = getStepTag() + "[DetermineVpcConnectionMethod.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

		// Update the step.
		update(ROLLBACK_STATUS, SUCCESS_RESULT);

		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + " ms.");
	}
}
