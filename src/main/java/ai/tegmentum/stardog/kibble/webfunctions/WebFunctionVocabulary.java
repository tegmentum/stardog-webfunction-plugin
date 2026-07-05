package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.base.Streams;
import com.google.common.collect.Lists;
import com.vdurmont.semver4j.Semver;

import java.util.List;
import java.util.ServiceLoader;

public enum WebFunctionVocabulary {
	call(Template.urlTemplate),
    callx(Template.urlTemplate),
    doc(Template.urlTemplate),
    cacheClear(Template.urlTemplate),
    cacheList(Template.urlTemplate),
    cacheLoad(Template.urlTemplate),
    cacheRefresh(Template.urlTemplate),
    pluginVersion(Template.urlTemplate),
    pluginVersions(Template.urlTemplate),
    pluginHash(Template.urlTemplate),
    agg(Template.urlTemplate),
    get(Template.urlTemplate),
    upgrade(Template.urlTemplate),
    IN_call_OUT(Template.urlTemplate),
    OUT_call_IN(Template.urlTemplate),
    compose(Template.urlTemplate),
    filter(Template.urlTemplate),
    map(Template.urlTemplate),
    memoize(Template.urlTemplate),
    partial(Template.urlTemplate),
    reduce(Template.urlTemplate),
    var(Template.urlTemplate),
    args(Template.urlTemplate),
    result(Template.urlTemplate),
    service(Template.urnTemplate),
    ;

    private String template;
    public static final String nsTemplate = "http://semantalytics.com/2021/03/ns/stardog/webfunction/%s/";

    WebFunctionVocabulary(final String template) {
        this.template = template;
    }

    public static String sparqlPrefix(final String prefixName, final String version) {
        return String.format("PREFIX %s: <%s>", prefixName, String.format(nsTemplate, version));
    }

    public String getMutableName() {
            return String.format(template, "latest", name());
    }

    public String getImmutableName() {
        return String.format(template, Version.PLUGIN_VERSION, name());
    }

    public List<String> getNames() {
        final List names = Lists.newArrayList(getImmutableName());
        if(Streams.stream(ServiceLoader.load(PluginVersionService.class).iterator()).map(PluginVersionService::pluginVersion).map(Semver::new).max(Semver::compareTo).get().isEqualTo(Version.PLUGIN_VERSION)) {
            names.add(getMutableName());
        }
        return names;
    }
}

class Template {
    public static final String urlTemplate = "http://semantalytics.com/2021/03/ns/stardog/webfunction/%s/%s";
    public static final String urnTemplate = "tag:semantalytics:stardog:webfunction:%s:%s";
}