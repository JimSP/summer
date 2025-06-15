package com.github.jimsp.summer.processor;

import com.github.jimsp.summer.annotations.Channel;
import com.github.jimsp.summer.annotations.Summer; // Changed from ContractFrom
import com.github.jimsp.summer.annotations.Summer.Mode; // Changed from ContractFrom.Mode
import com.github.jimsp.summer.messaging.Channel;
import com.google.auto.service.AutoService;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.squareup.javapoet.*;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

@SupportedAnnotationTypes("com.github.jimsp.summer.annotations.Summer") // Changed
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@AutoService(Processor.class)
public class OpenApiProcessor extends AbstractProcessor {
    private Filer filer;
    private Messager log;
    private Types types;
    private Elements elems;
    private RoundEnvironment round;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        filer = env.getFiler();
        log = env.getMessager();
        types = env.getTypeUtils();
        elems = env.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annots, RoundEnvironment rnd) {
        this.round = rnd;
        for (Element marker : rnd.getElementsAnnotatedWith(Summer.class)) { // Changed
            ContractRaw raw = new ContractRaw(marker.getAnnotation(Summer.class)); // Changed
            try {
                Map<String,String> src = generate(raw.spec());
                src.forEach(this::emit);
                patchServiceImpl(marker, raw);
            } catch(Exception ex){
                log.printMessage(Diagnostic.Kind.ERROR, "Processor error: "+ex, marker);
            }
        }
        return true;
    }

    private Map<String,String> generate(String spec) throws Exception {
        FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
        Path out = fs.getPath("/gen");
        CodegenConfigurator cfg = new CodegenConfigurator()
            .setGeneratorName("jaxrs-spec")
            .setInputSpec(Paths.get(spec).toUri().toString())
            .setOutputDir(out.toString())
            .setModelPackage("com.github.jimsp.summer.dto")
            .setApiPackage("com.github.jimsp.summer.api")
            .setInvokerPackage("com.github.jimsp.summer.invoker")
            .setAdditionalProperties(Map.of(
                "interfaceOnly",true,
                "useBeanValidation",true,
                "useLombokAnnotations",true,
                "modelMutable",false,
                "serializationLibrary","jackson"));
        new DefaultGenerator().opts(cfg.toClientOptInput()).generate();
        Map<String,String> map=new HashMap<>();
        Files.walk(out).filter(p->p.toString().endsWith(".java")).forEach(p->{
            try{
                String fq=p.toString().replace(out+"/src/main/java/","").replace('/', '.').replace(".java","");
                map.put(fq,Files.readString(p));
            }catch(IOException e){throw new UncheckedIOException(e);}
        });
        fs.close();
        return map;
    }

    private void emit(String fq,String code){
        try(Writer w=filer.createSourceFile(fq).openWriter()){
            w.write(code);
        } catch(IOException e){
            log.printMessage(Diagnostic.Kind.ERROR,"emit "+fq+": "+e, null);
        }
    }

    private void patchServiceImpl(Element marker, ContractRaw c) throws IOException {
        String res = marker.getSimpleName().toString().replace("Api","").toLowerCase();
        String dtoName = capitalize(res); // capitalize method is not yet defined
        ClassName dto = ClassName.get("com.github.jimsp.summer.dto", dtoName);
        String ifaceFq = "com.github.jimsp.summer.api."+dtoName+"ApiService";
        TypeElement ifaceEl = elems.getTypeElement(ifaceFq);
        if (ifaceEl == null) {
            log.printMessage(Diagnostic.Kind.ERROR, "Interface not found: " + ifaceFq, marker);
            return;
        }
        ExecutableElement m = ifaceEl.getEnclosedElements().stream()
            .filter(e->e.getKind()==ElementKind.METHOD)
            .map(e->(ExecutableElement)e).findFirst().orElse(null);

        if (m == null) {
            log.printMessage(Diagnostic.Kind.ERROR, "No method found in interface: " + ifaceFq, marker);
            return;
        }

        FieldSpec field;
        MethodSpec sendImpl;

        if(c.mode()==com.github.jimsp.summer.annotations.Summer.Mode.SYNC){ // Changed
            ClassName hType = ClassName.get("com.github.jimsp.summer.handlers", dtoName+"Handler");
            field = FieldSpec.builder(hType,"handler",Modifier.PRIVATE)
                .addAnnotation(ClassName.get("jakarta.inject","Inject")).build();
            sendImpl = MethodSpec.overriding(m)
                .addStatement("$T r = handler.handle(body)", dto)
                .addStatement("return $T.ok(r).build()", ClassName.get("jakarta.ws.rs.core","Response")).build();
        } else {
            String chanName = "channel."+c.cluster()+"."+res+"."+m.getSimpleName();
            ClassName concreteChannelImplClass = generateWrappers(dto, c, chanName, marker); // generateWrappers method is not yet defined

            // Parameterize the type for the channel field
            ParameterizedTypeName parameterizedChannelInterface = ParameterizedTypeName.get(
                ClassName.get(com.github.jimsp.summer.messaging.Channel.class), // Explicitly use the FQN of our Channel interface
                dto,                                             // The DTO class name (IN type)
                ClassName.get(Void.class)                        // Void.class for the OUT type
            );

            // Build the field using the parameterized type
            field = FieldSpec.builder(parameterizedChannelInterface, "channel", Modifier.PRIVATE)
                .addAnnotation(ClassName.get("jakarta.inject","Inject"))
                .addAnnotation(AnnotationSpec.builder(com.github.jimsp.summer.annotations.Channel.class) // Explicit FQN for annotation Channel
                    .addMember("value","$S", chanName).build())
                .build();
            sendImpl = MethodSpec.overriding(m)
                .addStatement("channel.send(body)")
                .addStatement("return $T.accepted().build()", ClassName.get("jakarta.ws.rs.core","Response"))
                .build();
        }

        TypeSpec impl = TypeSpec.classBuilder(dtoName+"ApiServiceImpl")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("jakarta.enterprise.context","ApplicationScoped"))
            .addSuperinterface(ClassName.bestGuess(ifaceFq))
            .addField(field)
            .addMethod(sendImpl)
            .build();
        JavaFile.builder("com.github.jimsp.summer.service", impl).build().writeTo(filer);
    }

    // TODO: Define capitalize and generateWrappers methods
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private ClassName generateWrappers(ClassName dto, ContractRaw c, String chanName, Element marker) {
        // Placeholder implementation for generateWrappers
        log.printMessage(Diagnostic.Kind.WARNING, "generateWrappers is not fully implemented yet.", marker);
        // This would typically generate several wrapper classes and return the outermost one.
        // For now, let's assume it generates a simple class in com.github.jimsp.summer.channels.generated
        String pkg = "com.github.jimsp.summer.channels.generated";
        String simpleName = capitalize(chanName.replaceAll("[^a-zA-Z0-9]", "")) + "ChannelImpl";

        // Create a placeholder TypeSpec for the channel implementation
        TypeSpec channelImpl = TypeSpec.classBuilder(simpleName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("jakarta.enterprise.context","ApplicationScoped"))
            .addAnnotation(AnnotationSpec.builder(com.github.jimsp.summer.annotations.Channel.class)
                .addMember("value", "$S", chanName).build())
            .addSuperinterface(ParameterizedTypeName.get(
                ClassName.get(com.github.jimsp.summer.messaging.Channel.class),
                dto,
                ClassName.get(Void.class) // Assuming ASYNC send-only for placeholder
             ))
            .addMethod(MethodSpec.methodBuilder("send")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(dto, "message")
                .addStatement("System.out.println(\"Sending message: \" + message)")
                .build())
             // Add other methods from Channel interface as needed for a complete placeholder
            .addMethod(MethodSpec.methodBuilder("sendAsync")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(CompletableFuture.class), ClassName.get(Void.class)))
                .addParameter(dto, "message")
                .addStatement("System.out.println(\"Sending async message: \" + message)")
                .addStatement("return $T.completedFuture(null)", CompletableFuture.class)
                .build())
            .addMethod(MethodSpec.methodBuilder("request")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(Void.class)) // Assuming Void for now
                .addParameter(dto, "message")
                .addStatement("System.out.println(\"Requesting message: \" + message)")
                .addStatement("return null")
                .build())
            .addMethod(MethodSpec.methodBuilder("requestAsync")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(CompletableFuture.class), ClassName.get(Void.class))) // Assuming Void for now
                .addParameter(dto, "message")
                .addStatement("System.out.println(\"Requesting async message: \" + message)")
                .addStatement("return $T.completedFuture(null)", CompletableFuture.class)
                .build())
            .build();

        try {
            JavaFile.builder(pkg, channelImpl).build().writeTo(filer);
        } catch (IOException e) {
            log.printMessage(Diagnostic.Kind.ERROR, "Failed to write placeholder channel: " + e.getMessage(), marker);
        }
        return ClassName.get(pkg, simpleName);
    }

    private record ContractRaw(
        String spec, String cluster, Summer.Mode mode, int maxRetries,
        boolean circuitBreaker, int cbFailureThreshold, int cbDelaySeconds,
        String dlq, int batchSize, String batchInterval
    ) {
        ContractRaw(Summer ann){
            this(
                resolve(ann.value()), resolve(ann.cluster()), ann.mode(), ann.maxRetries(),
                ann.circuitBreaker(), ann.cbFailureThreshold(), ann.cbDelaySeconds(),
                resolve(ann.dlq()), ann.batchSize(), resolve(ann.batchInterval())
            );
        }

        static String resolve(String v){
            if(v==null || !v.contains("${")) return v;
            Matcher m = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}").matcher(v);
            StringBuffer sb=new StringBuffer();
            while(m.find()){
                String key=m.group(1);
                String def=m.group(2);
                String envVal = System.getenv(key);
                String propVal = System.getProperty(key);
                String rep = "";

                if (propVal != null) {
                    rep = propVal;
                } else if (envVal != null) {
                    rep = envVal;
                } else if (def != null) {
                    rep = def;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }
}
