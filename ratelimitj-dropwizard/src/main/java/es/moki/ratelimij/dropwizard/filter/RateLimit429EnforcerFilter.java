package es.moki.ratelimij.dropwizard.filter;

import es.moki.ratelimij.dropwizard.annotation.Rate;
import es.moki.ratelimij.dropwizard.annotation.RateLimited;
import es.moki.ratelimitj.core.limiter.request.RequestLimitRule;
import es.moki.ratelimitj.core.limiter.request.RequestRateLimiter;
import es.moki.ratelimitj.core.limiter.request.RequestRateLimiterFactory;
import org.glassfish.jersey.server.model.AnnotatedMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Priority(Priorities.AUTHENTICATION + 1)
public class RateLimit429EnforcerFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimit429EnforcerFilter.class);

    private static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;

    @Inject private RequestRateLimiterFactory factory;

    @Context private HttpServletRequest request;
    @Context private ResourceInfo resource;
    @Context private SecurityContext securityContext;

    @Override
    public void filter(final ContainerRequestContext requestContext) {
        try {
            AnnotatedMethod method = new AnnotatedMethod(resource.getResourceMethod());
            RateLimited rateLimited = method.getAnnotation(RateLimited.class);

            RequestRateLimiter rateLimit = factory.getInstance(toLimitRules(rateLimited));
            KeyPart[] keyParts = rateLimited.keys();

            CharSequence key = KeyPart
                    .combineKeysParts(
                            rateLimited.groupKeyPrefix(),
                            Arrays.asList(keyParts),
                            request,
                            resource,
                            securityContext
                    )
                    .orElse(null);
            if (key == null) {
                LOG.warn("No keys were provided by the key providers '{}'",
                        Arrays.stream(keyParts)
                                .map(KeyPart::getClass)
                                .map(Object::toString)
                                .collect(Collectors.joining(", "))
                );
                return;
            }

            boolean overLimit = rateLimit.overLimitWhenIncremented(key.toString());
            if (overLimit) {
                if (!rateLimited.reportOnly()) {
                    LOG.info("rate-limit key '{}' over limit. HTTP Status 429 returned.", key);
                    requestContext.abortWith(Response.status(HTTP_STATUS_TOO_MANY_REQUESTS).build());
                } else {
                    LOG.info("rate-limit key '{}' over limit. ReportOnly is true, no action taken.", key);
                }
                LOG.debug("rate-limit key '{}' under limit.", key);
            }
        } catch (Exception e) {
            LOG.error("Error occurred checking rate-limit. Assuming under limit", e);
        }
    }

    private Set<RequestLimitRule> toLimitRules(RateLimited rateLimited) {
        return Arrays.stream(rateLimited.rates()).map(this::toLimitRule).collect(Collectors.toSet());
    }

    private RequestLimitRule toLimitRule(Rate rate) {
        return RequestLimitRule.of(Duration.ofMillis(rate.timeUnit().toMillis(rate.duration())), rate.limit());
    }
}