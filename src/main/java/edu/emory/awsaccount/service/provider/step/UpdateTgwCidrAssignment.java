package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayConnectionProfileAssignmentQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.EnterpriseObjectUpdateException;
import org.openeai.moa.XmlEnterpriseObjectException;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * This step will update the connection profile assignment with the VpcId of the new VPC.
 */
public class UpdateTgwCidrAssignment extends AbstractStep implements Step {
    private static final int REQUEST_TIMEOUT_INTERVAL_DEFAULT = 30_000;

    private ProducerPool networkOpsServiceProducerPool = null;
    private int requestTimeoutInterval = REQUEST_TIMEOUT_INTERVAL_DEFAULT;

    public void init (String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) throws StepException {
        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[UpdateTgwCidrAssignment.init] ";

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
        String LOGTAG = getStepTag() + "[UpdateTgwCidrAssignment.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        // previous steps set more inputs
        boolean createVpc = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_VPC_TYPE", "createVpc"));
        String vpcConnectionMethod = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "vpcConnectionMethod");

        if (!createVpc || !vpcConnectionMethod.equals("TGW")) {
            if (!createVpc)
                logger.info(LOGTAG + "Bypassing TGW connection profile assignment update since no VPC is being created");
            else
                logger.info(LOGTAG + "Bypassing TGW connection profile assignment update since VPC does not have TGW connectivity");

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
        String transitGatewayId = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "transitGatewayId");
        String vpcId = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "VpcId");
        logger.info(LOGTAG + "Replacing ProvisioningId " + provisioningId + " with VPC id " + vpcId
                + " in TGW connection profile for " + transitGatewayId);

        TransitGatewayConnectionProfileAssignment assignment;
        TransitGatewayConnectionProfileAssignmentQuerySpecification assignmentQuerySpec;
        try {
            assignment = (TransitGatewayConnectionProfileAssignment) getAppConfig().getObjectByType(TransitGatewayConnectionProfileAssignment.class.getName());
            assignmentQuerySpec = (TransitGatewayConnectionProfileAssignmentQuerySpecification) getAppConfig().getObjectByType(TransitGatewayConnectionProfileAssignmentQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            assignmentQuerySpec.setOwnerId(provisioningId);
            logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignmentQuerySpecification is: " + assignmentQuerySpec.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the TransitGatewayConnectionProfileAssignmentQuerySpecification. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the TransitGatewayConnectionProfileAssignmentQuerySpecification to XML. The exception is: " + e.getMessage();
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
            List<TransitGatewayConnectionProfileAssignment> assignments = assignment.query(assignmentQuerySpec, p2p);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignment.Query took " + elapsedTime + " ms. Returned " + assignments.size() + " results.");

            if (assignments.size() != 1) {
                String errMsg = "Invalid number of results returned from TransitGatewayConnectionProfileAssignment.Query. " +
                        assignments.size() + " results returned. Expected exactly 1.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }

            // change from reserved to assigned
            assignment = assignments.get(0);
            assignment.setOwnerId(vpcId);

            elapsedStartTime = System.currentTimeMillis();
            assignment.update(p2p);
            elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "TransitGatewayConnectionProfileAssignment.Update took " + elapsedTime + " ms.");
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred for TransitGatewayConnectionProfileAssignment.Query. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (EnterpriseObjectUpdateException e) {
            String errMsg = "An error occurred for TransitGatewayConnectionProfileAssignment.Update. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the moa. The exception is: " + e.getMessage();
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
        String LOGTAG = getStepTag() + "[UpdateTgwCidrAssignment.simulate] ";
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
        String LOGTAG = getStepTag() + "[UpdateTgwCidrAssignment.fail] ";
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

        String LOGTAG = getStepTag() + "[UpdateTgwCidrAssignment.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + " ms.");
    }
}
