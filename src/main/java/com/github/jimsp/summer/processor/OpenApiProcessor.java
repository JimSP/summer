package com.github.jimsp.summer.processor;

import com.github.jimsp.summer.annotations.Summer; import static com.github.jimsp.summer.annotations.Summer.Mode; // Qualifier import mantido via FQN nos AnnotationSpec // Interface Channel não é usada diretamente; refletimos via JavaPoet

import com.google.auto.service.AutoService; import com.google.common.jimfs.Configuration; import com.google.common.jimfs.Jimfs; import com.squareup.javapoet.*; import jakarta.inject.Inject; import org.openapitools.codegen.DefaultGenerator; import org.openapitools.codegen.config.CodegenConfigurator;

import javax.annotation.processing.; import javax.lang.model.SourceVersion; import javax.lang.model.element.; import javax.lang.model.util.Elements; import javax.lang.model.util.Types; import javax.tools.Diagnostic; import java.io.IOException; import java.io.UncheckedIOException; import java.io.Writer; import java.nio.file.; import java.util.; import java.util.concurrent.CompletableFuture; import java.util.regex.Matcher; import java.util.regex.Pattern; import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.github.jimsp.summer.annotations.Summer") @SupportedSourceVersion(SourceVersion.RELEASE_17) @AutoService(Processor.class) public class OpenApiProcessor extends AbstractProcessor { private Filer filer; private Messager log; private Elements elems;

@Override
public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    filer = env.getFiler();
    log   = env.getMessager();
    elems = env.getElementUtils();
}

/* ------------------------------------------------ process() */
@Override
public boolean process(Set<? extends TypeElement> annots, RoundEnvironment round) {
    for (Element marker : round.getElementsAnnotatedWith(Summer.class)) {
        ContractRaw cfg = new ContractRaw(marker.getAnnotation(Summer.class));
        try {
            generateOpenApiSources(cfg.spec()).forEach(this::emit);
            patchServiceImpl(marker, cfg);
        } catch (Exception ex) {
            log.printMessage(Diagnostic.Kind.ERROR, "Processor error: " + ex, marker);
        }
    }
    return true;
}

/* ------------------------------------------------ generate OpenAPI */
private Map<String, String> generateOpenApiSources(String spec) throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
        Path out = fs.getPath("/gen");
        new DefaultGenerator().opts(openApiCfg(spec, out).toClientOptInput()).generate();
        Map<String, String> src = new HashMap<>();
        Files.walk(out)
             .filter(p -> p.toString().endsWith(".java"))
             .forEach(p -> {
                 try {
                     String fq = p.toString()
                                   .replace(out + "/src/main/java/", "")
                                   .replace('/', '.').replace(".java", "");
                     src.put(fq, Files.readString(p));
                 } catch (IOException e) {
                     throw new UncheckedIOException(e);
                 }
             });
        return src;
    }
}

private CodegenConfigurator openApiCfg(String spec, Path out) {
    return new CodegenConfigurator()
            .setGeneratorName("jaxrs-spec")
            .setInputSpec(Paths.get(spec).toUri().toString())
            .setOutputDir(out.toString())
            .setModelPackage("com.github.jimsp.summer.dto")
            .setApiPackage("com.github.jimsp.summer.api")
            .setInvokerPackage("com.github.jimsp.summer.invoker")
            .setAdditionalProperties(Map.of(
                    "interfaceOnly", true,
                    "useBeanValidation", true,
                    "useLombokAnnotations", true,
                    "modelMutable", false,
                    "serializationLibrary", "jackson"));
}

private void emit(String fq, String code) {
    try (Writer w = filer.createSourceFile(fq).openWriter()) {
        w.write(code);
    } catch (IOException e) {
        log.printMessage(Diagnostic.Kind.ERROR, "emit " + fq + ": " + e);
    }
}

/* ------------------------------------------------ patch generated ApiServiceImpl */
private void patchServiceImpl(Element marker, ContractRaw c) throws IOException {
    String res      = marker.getSimpleName().toString().replace("Api", "").toLowerCase();
    String dtoName  = toUpperCamel(res);
    ClassName dto   = ClassName.get("com.github.jimsp.summer.dto", dtoName);
    String ifaceFq  = "com.github.jimsp.summer.api." + dtoName + "ApiService";

    TypeElement iface = elems.getTypeElement(ifaceFq);
    if (iface == null) {
        log.printMessage(Diagnostic.Kind.ERROR, "Interface not found: " + ifaceFq, marker);
        return;
    }
    ExecutableElement m = iface.getEnclosedElements().stream()
                               .filter(e -> e.getKind() == ElementKind.METHOD)
                               .map(ExecutableElement.class::cast)
                               .findFirst().orElse(null);
    if (m == null) {
        log.printMessage(Diagnostic.Kind.ERROR, "No method in interface: " + ifaceFq, marker);
        return;
    }

    FieldSpec field;
    MethodSpec implMethod;

    if (c.mode() == Mode.SYNC) {
        ClassName handler = ClassName.get("com.github.jimsp.summer.handlers", dtoName + "Handler");
        field = FieldSpec.builder(handler, "handler", Modifier.PRIVATE)
                         .addAnnotation(Inject.class).build();
        implMethod = MethodSpec.overriding(m)
                               .addStatement("$T r = handler.handle(body)", dto)
                               .addStatement("return $T.ok(r).build()", ClassName.get("jakarta.ws.rs.core", "Response"))
                               .build();
    } else {
        String chanName = "channel." + c.cluster() + "." + res + "." + m.getSimpleName();
        generateWrappers(dto, c, chanName, marker); // outermost wrapper is generated

        ParameterizedTypeName chanType = ParameterizedTypeName.get(
                ClassName.get("com.github.jimsp.summer.messaging", "Channel"),
                dto,
                ClassName.get(Void.class));

        field = FieldSpec.builder(chanType, "channel", Modifier.PRIVATE)
                         .addAnnotation(Inject.class)
                         .addAnnotation(AnnotationSpec.builder(com.github.jimsp.summer.annotations.Channel.class)
                             .addMember("value", "$S", chanName).build())
                         .build();
        implMethod = MethodSpec.overriding(m)
                               .addStatement("channel.send(body)")
                               .addStatement("return $T.accepted().build()", ClassName.get("jakarta.ws.rs.core", "Response"))
                               .build();
    }

    TypeSpec impl = TypeSpec.classBuilder(dtoName + "ApiServiceImpl")
                            .addModifiers(Modifier.PUBLIC)
                            .addAnnotation(ClassName.get("jakarta.enterprise.context", "ApplicationScoped"))
                            .addSuperinterface(ClassName.bestGuess(ifaceFq))
                            .addField(field)
                            .addMethod(implMethod)
                            .build();
    JavaFile.builder("com.github.jimsp.summer.service", impl).build().writeTo(filer);
}

/* ------------------------------------------------ helper: camel case */
private String toUpperCamel(String s) {
    if (s == null || s.isBlank()) return s;
    return Arrays.stream(s.split("[\\-_]"))
                 .filter(p -> !p.isBlank())
                 .map(p -> p.substring(0,

