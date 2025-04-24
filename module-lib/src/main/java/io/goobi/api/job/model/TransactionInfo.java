package io.goobi.api.job.model;

import java.time.OffsetDateTime;

public class TransactionInfo {

    /**
     * Transaction id
     **/
    private Integer transactionId;

    /**
     * Transaction start date and time
     **/
    private OffsetDateTime startedAt;

    /**
     * Transaction last request date and time
     **/
    private OffsetDateTime lastRequest;

    public enum StateEnum {

        ACTIVE(String.valueOf("active")),
        COMMIT(String.valueOf("commit")),
        ROLLBACK(String.valueOf("rollback"));

        String value;

        StateEnum(String v) {
            value = v;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }

    }

    /**
     * Transaction's current state
     **/
    private StateEnum state;

    /**
     * Transaction id
     *
     * @return transactionId
     **/
    public Integer getTransactionId() {
        return transactionId;
    }

    /**
     * Set transactionId
     **/
    public void setTransactionId(Integer transactionId) {
        this.transactionId = transactionId;
    }

    public TransactionInfo transactionId(Integer transactionId) {
        this.transactionId = transactionId;
        return this;
    }

    /**
     * Transaction start date and time
     *
     * @return startedAt
     **/
    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    /**
     * Set startedAt
     **/
    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public TransactionInfo startedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
        return this;
    }

    /**
     * Transaction last request date and time
     *
     * @return lastRequest
     **/
    public OffsetDateTime getLastRequest() {
        return lastRequest;
    }

    /**
     * Set lastRequest
     **/
    public void setLastRequest(OffsetDateTime lastRequest) {
        this.lastRequest = lastRequest;
    }

    public TransactionInfo lastRequest(OffsetDateTime lastRequest) {
        this.lastRequest = lastRequest;
        return this;
    }

    /**
     * Transaction&#39;s current state
     *
     * @return state
     **/
    public StateEnum getState() {
        return state;
    }

    /**
     * Set state
     **/
    public void setState(StateEnum state) {
        this.state = state;
    }

    public TransactionInfo state(StateEnum state) {
        this.state = state;
        return this;
    }

    /**
     * Create a string representation of this pojo.
     **/
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class TransactionInfo {\n");
        sb.append("    transactionId: ").append(toIndentedString(transactionId)).append("\n");
        sb.append("    startedAt: ").append(toIndentedString(startedAt)).append("\n");
        sb.append("    lastRequest: ").append(toIndentedString(lastRequest)).append("\n");
        sb.append("    state: ").append(toIndentedString(state)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Convert the given object to string with each line indented by 4 spaces (except the first line).
     */
    private static String toIndentedString(Object o) {
        if (o == null) {
            return "null";
        }
        return o.toString().replace("\n", "\n    ");
    }
}
