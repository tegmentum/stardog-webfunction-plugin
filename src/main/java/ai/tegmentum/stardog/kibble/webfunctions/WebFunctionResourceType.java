package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.security.SecurityResourceType;

public final class WebFunctionResourceType implements SecurityResourceType {

    public static final WebFunctionResourceType INSTANCE = new WebFunctionResourceType();

    public static final String ID = "web-function";

    private WebFunctionResourceType() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isDatabaseType() {
        return true;
    }
}
