package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.AbstractFunction;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.google.common.collect.Range;
import com.stardog.stark.Datatype;
import com.stardog.stark.Value;
import com.stardog.stark.impl.IntegerLiteral;
import com.stardog.stark.impl.StringLiteral;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Base64;

import static com.stardog.stark.Values.*;

public class Get extends AbstractFunction implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.get;

    private static final int URL_INDEX = 0;
    private static final int CONNECTION_TIMEOUT_INDEX = 1;
    private static final int READ_TIMEOUT_INDEX = 2;
    private static final int READ_TIMEOUT_DEFAULT = Math.toIntExact(Duration.ofSeconds(60).toMillis());
    private static final int CONNECTION_TIMEOUT_DEFAULT = Math.toIntExact(Duration.ofSeconds(30).toMillis());


    public Get() {
        super(Range.atMost(3), names.getNames().toArray(new String[0]));
    }

    public Get(final Get get) {
        super(get);
    }

    @Override
    protected ValueOrError internalEvaluate(final Value... values) {
        final int readTimeout;
        if(values.length == READ_TIMEOUT_INDEX + 1) {
            if(assertIntegerLiteral(values[READ_TIMEOUT_INDEX])) {
                readTimeout = ((IntegerLiteral)values[READ_TIMEOUT_INDEX]).intValue();
            } else {
                return ValueOrError.Error;
            }
        } else {
            readTimeout = READ_TIMEOUT_DEFAULT;
        }
        final int connectionTimeout;
        if(values.length >= CONNECTION_TIMEOUT_INDEX + 1) {
            if(assertIntegerLiteral(values[CONNECTION_TIMEOUT_INDEX])) {
                connectionTimeout = ((IntegerLiteral)values[CONNECTION_TIMEOUT_INDEX]).intValue();
            } else {
                return ValueOrError.Error;
            }
        } else {
            connectionTimeout = CONNECTION_TIMEOUT_DEFAULT;
        }

        final String url;

        if (assertIRI(values[URL_INDEX])) {
            url = values[URL_INDEX].toString();
        } else if(assertStringLiteral(values[URL_INDEX])) {
            url = ((StringLiteral) values[URL_INDEX]).label();
        } else {
            return ValueOrError.Error;
        }

        try {
            final URLConnection conn;
            conn = new URL(url).openConnection();
            conn.setConnectTimeout(connectionTimeout);
            conn.setReadTimeout(readTimeout);
            conn.connect();
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copy(conn.getInputStream(), baos);
            return ValueOrError.General.of(literal(Base64.getEncoder().encodeToString(baos.toByteArray()), Datatype.BASE64BINARY));
        } catch (IOException e) {
            return ValueOrError.Error;
        }
    }

    @Override
    public Get copy() {
        return new Get(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }
}
