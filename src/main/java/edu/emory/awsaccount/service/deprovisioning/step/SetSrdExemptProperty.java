package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.EnterpriseObjectUpdateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * Set the srdExempt account property to prevent SRDs from running against this account.
 *
 * @author Darryl Pierce (dpierce@surgeforward.com
 * @version 1.0 - 19 May 2020
 */
public class SetSrdExemptProperty extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "SetSrdExemptProperty";
    private ProducerPool producerPool;

    public void init(String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {
        super.init(deprovisioningId, props, aConfig, adp);

        String LOGTAG = createLogTag("init");

        logger.info(LOGTAG + "Getting custom step properties...");
        ProducerPool producerPool = null;

        try {
            producerPool = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(producerPool);
        } catch (EnterpriseConfigurationObjectException error) {
            String message = "An error occurred retrieving an object from AppConfig. The exception is: " + error.getMessage();
            logger.fatal(LOGTAG + message);
            throw new StepException(message);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    private void setAwsAccountServiceProducerPool(ProducerPool producerPool) {
        this.producerPool = producerPool;
    }

    @Override
    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("simulate");
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("isAuthorized", "true");

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("run");

        logger.info(LOGTAG + "Begin running the step.");
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
        logger.info(LOGTAG + "Setting the srdExempt to true.");

        String accountId = getStepPropertyValue("AUTHORIZE_REQUESTOR", "accountId");
        logger.info(LOGTAG + "The accountId is: " + accountId);

        Account account = null;
        AccountQuerySpecification querySpec = null;
        try {
            account = (Account) getAppConfig().getObjectByType(Account.class.getName());
            querySpec = (AccountQuerySpecification) getAppConfig().getObjectByType(AccountQuerySpecification.class.getName());
        } catch (EnterpriseConfigurationObjectException error) {
            String message = "An error occurred retrieving an object from AppConfig. The exception is: " + error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            querySpec.setAccountId(accountId);
        } catch (EnterpriseFieldException error) {
            String message = "An error occurred setting the values of the query spec. The exception is: " + error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            logger.info(LOGTAG + "Account query spec: " + querySpec.toXmlString());
        } catch (XmlEnterpriseObjectException error) {
            String message = "An error occurred serializing the query spec to XML. The exception is: " + error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        RequestService rs = null;
        try {
            rs = (RequestService) this.producerPool.getExclusiveProducer();
        } catch (JMSException error) {
            String message = "An error occurred getting a producer from the pool. The exception is: " + error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        List results = null;

        try {
            long queryStartTime = System.currentTimeMillis();
            results = account.query(querySpec, rs);
            long queryTime = System.currentTimeMillis() - queryStartTime;
            logger.info(LOGTAG + "Queried for account in " + queryTime + " ms.");
        } catch (EnterpriseObjectQueryException error) {
            String message = "An error occurred creating the object. The exception is: " + error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } finally {
            this.producerPool.releaseProducer((MessageProducer) rs);
        }

        boolean updatedPropValue = false;
        if (results.size() == 1) {
            account = (Account) results.get(0);
            List<Property> props = account.getProperty();
            for (int index = 0; index < props.size(); index++) {
                Property prop = props.get(index);
                if (prop.getKey().equalsIgnoreCase("srdExempt")) {
                    try {
                        prop.setValue("true");
                    } catch (EnterpriseFieldException error) {
                        String message = "An error occurred setting field values. The exception is: " + error.getMessage();
                        logger.error(LOGTAG + message);
                        throw new StepException(message, error);
                    }
                    updatedPropValue = true;
                }
            }

            if (updatedPropValue) {
                // Get a producer from the pool
                rs = null;
                try {
                    rs = (RequestService) this.producerPool.getExclusiveProducer();
                } catch (JMSException error) {
                    String message = "An error occurred getting a producer from the pool. The exception is: " + error.getMessage();
                    logger.error(LOGTAG + message);
                    throw new StepException(message, error);
                }

                try {
                    long updateStartTime = System.currentTimeMillis();
                    account.update(rs);
                    long updateTime = System.currentTimeMillis() - updateStartTime;
                    logger.info(LOGTAG + "Updated Account in " + updateTime + " ms.");
                } catch (EnterpriseObjectUpdateException error) {
                    String message = "An error occurred updating the object. The exception is: " + error.getMessage();
                    logger.error(LOGTAG + message);
                    throw new StepException(message, error);
                } finally {
                    this.producerPool.releaseProducer((MessageProducer) rs);
                }
            } else {
                logger.info(LOGTAG + "srdExempt was not true. There is nothing to update.");
                addResultProperty("srdExempt", "attempted to update, but nothing to update");
            }
        } else {
            String message = "Invalid number of accounts returned. Expected 1, received " + results.size();
            logger.error(LOGTAG + message);
            throw new StepException(message);
        }

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("fail");
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

        update(COMPLETED_STATUS, FAILURE_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("rollback");

        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private String createLogTag(String method) {
        return getStepTag() + "[" + LOGTAG_NAME + "." + method + "] ";
    }
}
