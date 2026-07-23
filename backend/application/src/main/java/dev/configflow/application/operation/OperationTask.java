package dev.configflow.application.operation;

/**
 * One unit of queued work.
 *
 * <p>Checked exceptions are allowed because the engines throw them: the queue catches
 * whatever comes out and records the operation as failed rather than letting a worker
 * thread die.</p>
 */
@FunctionalInterface
public interface OperationTask {

    void run(OperationContext context) throws Exception;
}
