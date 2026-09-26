package com.shortly.videoservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller declare {@code CallerIdentity caller} as a parameter.
 *
 * <p>Without this, every controller either reads the raw {@code X-User-Id} header - quietly
 * bypassing the gateway-secret check - or repeats the same attribute lookup.
 */
@Component
public class CallerIdentityArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CallerIdentity.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer container,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        CallerIdentity identity = fromRequest(webRequest);
        if (identity != null) {
            return identity;
        }
        /*
         * Thrown rather than returning null. A null would reach the service method and surface as
         * a NullPointerException - a 500 for what is really a routing mistake.
         */
        throw new CallerIdentityNotResolvedException();
    }

    /**
     * Reads the attribute straight off the servlet request.
     * <p>
     * Deliberately not via {@code NativeWebRequest#getAttribute}. Going through the abstraction
     * works in production and quietly fails to see attributes set in a MockMvc test, which is a
     * bad trade: the indirection buys nothing and costs test fidelity.
     */
    private static CallerIdentity fromRequest(NativeWebRequest webRequest) {
        Object value = null;
        if (webRequest instanceof ServletWebRequest servletWebRequest) {
            value = servletWebRequest.getRequest()
                    .getAttribute(CallerIdentity.REQUEST_ATTRIBUTE);
        } else {
            value = webRequest.getAttribute(
                    CallerIdentity.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        }
        return value instanceof CallerIdentity identity ? identity : null;
    }

    /** No gateway identity on a request whose handler required one. */
    public static class CallerIdentityNotResolvedException extends RuntimeException {

        public CallerIdentityNotResolvedException() {
            super("Request carried no gateway-asserted caller identity. "
                    + "It did not arrive through the API gateway, or the gateway did not set "
                    + "X-User-Id.");
        }
    }
}
