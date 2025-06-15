package com.github.jimsp.summer.processor;

import com.github.jimsp.summer.annotations.Summer;
import static com.github.jimsp.summer.annotations.Summer.Mode;

import com.google.auto.service.AutoService;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.squareup.javapoet.*;

import jakarta.inject.Inject;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.*;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.github.jimsp.summer.annotations.Summer")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@AutoService(Processor.class)
public class OpenApiProcessor extends AbstractProcessor {

    private Filer filer;
    private Messager log;
    private Elements elems;

    @Override public synchronized void init(ProcessingEnvironment env) {
        filer = env.getFiler();
        log   = env.getMessager();
        elems = env.getElementUtils();
    }

    @Override public boolean process(Set<? extends TypeElement> ann, RoundEnvironment round) {
        for (Element e : round.getElementsAnnotatedWith(Summer.class)) {
            ContractRaw cfg = new ContractRaw(e.getAnnotation(Summer.class));
            try {
                generateSources(cfg.spec()).forEach(this::emit);
                patchServiceImpl(e, cfg);
            } catch (Exception ex) {
                log.printMessage(Diagnostic.Kind.ERROR, "Processor error: " + ex, e);
            }
        }
        return true;
    }

    /* OpenAPI -> fontes */
    private Map<String,String> generateSources(String spec) throws Exception {
        try(FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path out = fs.getPath("/gen");
            new DefaultGenerator().opts(openApiCfg(spec,out).toClientOptInput()).generate();
            Map<String,String> map=new HashMap<>();
            Files.walk(out).filter(p->p.toString().endsWith(".java")).forEach(p->{
                try{
                    String fq=p.toString().replace(out+"/src/main/java/","")
                                          .replace('/','.')
                                          .replace(".java","");
                    map.put(fq, Files.readString(p));
                }catch(IOException x){throw new UncheckedIOException(x);}
            });
            return map;
        }
    }
    private CodegenConfigurator openApiCfg(String spec, Path out){
        return new CodegenConfigurator()
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
    }
    private void emit(String fq,String code){
        try(Writer w=filer.createSourceFile(fq).openWriter()){ w.write(code); }
        catch(IOException x){ log.printMessage(Diagnostic.Kind.ERROR,"emit "+fq+": "+x); }
    }

    private void patchServiceImpl(Element marker, ContractRaw c) throws IOException {
        String base   = marker.getSimpleName().toString().replace("Api","").toLowerCase();
        String dtoNm  = toUpperCamel(base);
        ClassName dto = ClassName.get("com.github.jimsp.summer.dto", dtoNm);
        String ifaceFq= "com.github.jimsp.summer.api."+dtoNm+"ApiService";

        TypeElement api = elems.getTypeElement(ifaceFq);
        if(api==null){ log.printMessage(Diagnostic.Kind.ERROR,"Interface "+ifaceFq+" not found",marker); return; }
        ExecutableElement m = api.getEnclosedElements().stream()
                .filter(e->e.getKind()==ElementKind.METHOD)
                .map(ExecutableElement.class::cast).findFirst().orElse(null);
        if(m==null){ log.printMessage(Diagnostic.Kind.ERROR,"No methods in "+ifaceFq,marker); return;}

        FieldSpec field; MethodSpec method;

        if(c.mode()==Mode.SYNC){
            ClassName handler = ClassName.get("com.github.jimsp.summer.handlers", dtoNm+"Handler");
            field   = FieldSpec.builder(handler,"handler",Modifier.PRIVATE).addAnnotation(Inject.class).build();
            method  = MethodSpec.overriding(m)
                      .addStatement("return $T.ok(handler.handle(body)).build()", ClassName.get("jakarta.ws.rs.core","Response"))
                      .build();
        }else{
            String chan="channel."+c.cluster()+"."+base+"."+m.getSimpleName();
            generateWrapperPlaceholder(dto, chan, marker);

            ParameterizedTypeName chanType=ParameterizedTypeName.get(
                ClassName.get("com.github.jimsp.summer.messaging","Channel"), dto, ClassName.get(Void.class));

            field = FieldSpec.builder(chanType,"channel",Modifier.PRIVATE)
                    .addAnnotation(Inject.class)
                    .addAnnotation(AnnotationSpec.builder(com.github.jimsp.summer.annotations.Channel.class)
                                  .addMember("value","$S",chan).build())
                    .build();
            method= MethodSpec.overriding(m)
                    .addStatement("channel.send(body)")
                    .addStatement("return $T.accepted().build()", ClassName.get("jakarta.ws.rs.core","Response"))
                    .build();
        }

        TypeSpec impl = TypeSpec.classBuilder(dtoNm+"ApiServiceImpl")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ClassName.get("jakarta.enterprise.context","ApplicationScoped"))
                .addSuperinterface(ClassName.bestGuess(ifaceFq))
                .addField(field)
                .addMethod(method)
                .build();
        JavaFile.builder("com.github.jimsp.summer.service",impl).build().writeTo(filer);
    }

    private static String toUpperCamel(String s){
        if(s==null||s.isBlank()) return s;
        return Arrays.stream(s.split("[\-_]"))
                     .filter(Predicate.not(String::isBlank))
                     .map(p->p.substring(0,1).toUpperCase()+p.substring(1).toLowerCase())
                     .collect(Collectors.joining());
    }

    private void generateWrapperPlaceholder(ClassName dto,String chan,Element loc){
        String pkg="com.github.jimsp.summer.channels.generated";
        String cls=toUpperCamel(chan.replaceAll("[^a-zA-Z0-9]",""))+"ChannelImpl";

        TypeSpec tp=TypeSpec.classBuilder(cls)
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(ClassName.get("jakarta.enterprise.context","ApplicationScoped"))
          .addAnnotation(AnnotationSpec.builder(com.github.jimsp.summer.annotations.Channel.class)
               .addMember("value","$S",chan).build())
          .addSuperinterface(ParameterizedTypeName.get(
               ClassName.get("com.github.jimsp.summer.messaging","Channel"), dto, ClassName.get(Void.class)))
          .addMethod(MethodSpec.methodBuilder("send").addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC).addParameter(dto,"m")
               .addStatement("System.out.println($S+m)","SEND ").build())
          .addMethod(MethodSpec.methodBuilder("sendAsync").addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC)
               .returns(ParameterizedTypeName.get(ClassName.get(CompletableFuture.class),ClassName.get(Void.class)))
               .addParameter(dto,"m")
               .addStatement("return $T.completedFuture(null)",CompletableFuture.class).build())
          .addMethod(MethodSpec.methodBuilder("request").addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC).returns(ClassName.get(Void.class))
               .addParameter(dto,"m")
               .addStatement("return null").build())
          .addMethod(MethodSpec.methodBuilder("requestAsync").addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC)
               .returns(ParameterizedTypeName.get(ClassName.get(CompletableFuture.class),ClassName.get(Void.class)))
               .addParameter(dto,"m")
               .addStatement("return $T.completedFuture(null)",CompletableFuture.class).build())
          .build();
        try{ JavaFile.builder(pkg,tp).build().writeTo(filer);}
        catch(IOException x){ log.printMessage(Diagnostic.Kind.ERROR,"wrapper gen: "+x,loc);}
    }

    /* resolve ${KEY:default} ------------------------------------------------ */
    private static String resolve(String v){
        if(v==null||!v.contains("${")) return v;
        Matcher m=Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}").matcher(v);
        StringBuffer sb=new StringBuffer();
        while(m.find()){
            String rep=System.getProperty(m.group(1),
                         System.getenv().getOrDefault(m.group(1),
                         m.group(2)==null?"":m.group(2)));
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /* record */
    private record ContractRaw(String spec,String cluster,Mode mode,int maxRetries,
                               boolean circuitBreaker,int cbFT,int cbDelay,
                               String dlq,int batchSize,String batchInt){
        ContractRaw(Summer a){
            this(resolve(a.value()),resolve(a.cluster()),a.mode(),a.maxRetries(),
                 a.circuitBreaker(),a.cbFailureThreshold(),a.cbDelaySeconds(),
                 resolve(a.dlq()),a.batchSize(),resolve(a.batchInterval()));
        }
    }
}
