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

/** Lets a controller declare {@code CallerIdentity caller} as a parameter. Without this, every
 * check - or repeats the same attribute lookup. */
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
        /** Thrown rather than returning null. */
        throw new CallerIdentityNotResolvedException();
    }

    /** Reads the attribute straight off the servlet request. Deliberately not via {@code
     * NativeWebRequest#getAttribute}. */
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
