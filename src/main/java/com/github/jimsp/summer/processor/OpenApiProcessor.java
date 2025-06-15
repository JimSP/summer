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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.regex.*;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.github.jimsp.summer.annotations.Summer")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@AutoService(Processor.class)
public class OpenApiProcessor extends AbstractProcessor {

    private Filer    filer;
    private Messager log;
    private Elements elems;

    /* -------- init -------- */
    @Override 
    public synchronized void init(ProcessingEnvironment env){
        filer = env.getFiler();
        log   = env.getMessager();
        elems = env.getElementUtils();
    }

    /* -------- process loop -------- */
    @Override 
    public boolean process(Set<? extends TypeElement> ann, RoundEnvironment rnd){
        for(Element e : rnd.getElementsAnnotatedWith(Summer.class)){
            ContractRaw cfg = new ContractRaw(e.getAnnotation(Summer.class));
            try{
                generateSources(cfg).forEach(this::emit);
                patchServiceImpl(e, cfg);
            }catch(IOException | UncheckedIOException io){
                log.printMessage(Diagnostic.Kind.ERROR,"I/O: "+io.getMessage(),e);
                throw new RuntimeException(io);      // fail-fast
            }
        }
        return true;
    }

    /* -------- OpenAPI → .java -------- */
    private Map<String,String> generateSources(ContractRaw c) throws IOException{
        try(FileSystem fs = Jimfs.newFileSystem(Configuration.unix())){
            Path out = fs.getPath("/gen");
            new DefaultGenerator().opts(openCfg(c,out).toClientOptInput()).generate();

            Map<String,String> map=new HashMap<>();
            Files.walk(out).filter(p->p.toString().endsWith(".java")).forEach(p->{
                try{
                    String fq = p.toString()
                                  .replace(out+"/src/main/java/","")
                                  .replace('/','.')
                                  .replace(".java","");
                    map.put(fq, Files.readString(p));
                }catch(IOException x){throw new UncheckedIOException(x);}
            });
            return map;
        }
    }
    private CodegenConfigurator openCfg(ContractRaw c, Path out){
        return new CodegenConfigurator()
            .setGeneratorName("jaxrs-spec")
            .setInputSpec(Paths.get(c.spec).toUri().toString())
            .setOutputDir(out.toString())
            .setModelPackage(c.dtoPkg)
            .setApiPackage(c.apiPkg)
            .setInvokerPackage(c.basePkg+".invoker")
            .setAdditionalProperties(Map.of(
                    "interfaceOnly",true,
                    "useBeanValidation",true,
                    "useLombokAnnotations",true,
                    "modelMutable",false,
                    "serializationLibrary","jackson"));
    }
    private void emit(String fq,String code) throws IOException{
        try(Writer w = filer.createSourceFile(fq).openWriter()){ w.write(code); }
    }

    /* -------- patch ServiceImpl -------- */
    private void patchServiceImpl(Element marker, ContractRaw c) throws IOException{
        String baseName = marker.getSimpleName().toString().replace("Api","");
        ClassName dto   = ClassName.get(c.dtoPkg, baseName);
        String ifaceFq  = c.apiPkg+"."+baseName+"ApiService";

        TypeElement api = elems.getTypeElement(ifaceFq);
        if(api==null) throw new IOException("Interface "+ifaceFq+" not generated");

        ExecutableElement m = api.getEnclosedElements().stream()
                                 .filter(e->e.getKind()==ElementKind.METHOD)
                                 .map(ExecutableElement.class::cast)
                                 .findFirst()
                                 .orElseThrow(() -> new IOException("No methods in "+ifaceFq));

        /* defensive check: require at least one parameter */
        if(m.getParameters().isEmpty())
            throw new IOException("Method "+m.getSimpleName()+" in "+ifaceFq+
                                  " has no parameters – cannot map to Channel/Handler.");

        String param = m.getParameters().get(0).getSimpleName().toString();

        FieldSpec field; MethodSpec method;

        /* ---------- SYNC ---------- */
        if(c.mode == Mode.SYNC){
            ClassName handler = ClassName.get(c.handlerPkg, baseName+"Handler");
            field = FieldSpec.builder(handler,"handler",Modifier.PRIVATE)
                             .addAnnotation(Inject.class).build();
            method= MethodSpec.methodBuilder(m.getSimpleName().toString())
                   .addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                   .returns(ClassName.get("jakarta.ws.rs.core","Response"))
                   .addParameter(dto,param)
                   .addStatement("return $T.ok(handler.handle($N)).build()",
                                 ClassName.get("jakarta.ws.rs.core","Response"), param)
                   .build();
        }
        /* ---------- ASYNC request/reply ---------- */
        else{
            String sendChan  = "channel."+c.cluster+"."+baseName.toLowerCase()+"."+m.getSimpleName();
            String replyChan = c.replyChan.isBlank()
                               ? "channel."+c.cluster+"."+baseName.toLowerCase()+".reply"
                               : c.replyChan;

            TypeName outType = ClassName.get("java.lang","Object");           // generic placeholder
            generatePlaceholderChannel(dto,outType,sendChan,replyChan,c.channelPkg,marker);

            ParameterizedTypeName chanType = ParameterizedTypeName.get(
                    ClassName.get("com.github.jimsp.summer.messaging","Channel"), dto, outType);

            field = FieldSpec.builder(chanType,"channel",Modifier.PRIVATE)
                    .addAnnotation(Inject.class)
                    .addAnnotation(AnnotationSpec.builder(com.github.jimsp.summer.annotations.Channel.class)
                               .addMember("value","$S", sendChan).build())
                    .build();
            method= MethodSpec.methodBuilder(m.getSimpleName().toString())
                   .addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                   .returns(ClassName.get("jakarta.ws.rs.core","Response"))
                   .addParameter(dto,param)
                   .addStatement("$T reply = channel.request($N)", outType, param)
                   .addStatement("return $T.ok(reply).build()",
                                 ClassName.get("jakarta.ws.rs.core","Response"))
                   .build();
        }

        TypeSpec impl = TypeSpec.classBuilder(baseName+"ApiServiceImpl")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ClassName.get("jakarta.enterprise.context","ApplicationScoped"))
                .addSuperinterface(ClassName.bestGuess(ifaceFq))
                .addField(field).addMethod(method).build();
        JavaFile.builder(c.servicePkg,impl).build().writeTo(filer);
    }

    /* -------- placeholder Channel -------- */
    private void generatePlaceholderChannel(ClassName in,TypeName out,
                                            String sendChan,String replyChan,
                                            String pkg,Element loc) throws IOException{
        String cls = toUpperCamel(sendChan.replaceAll("[^a-zA-Z0-9]",""))+"ChannelImpl";
        TypeName qIn  = ParameterizedTypeName.get(ClassName.get(ConcurrentLinkedQueue.class), in);
        TypeName qOut = ParameterizedTypeName.get(ClassName.get(ConcurrentLinkedQueue.class), out);

        TypeSpec impl = TypeSpec.classBuilder(cls)
           .addModifiers(Modifier.PUBLIC)
           .addAnnotation(ClassName.get("jakarta.enterprise.context","ApplicationScoped"))
           .addAnnotation(AnnotationSpec.builder(com.github.jimsp.summer.annotations.Channel.class)
                 .addMember("value","$S", sendChan).build())
           .addSuperinterface(ParameterizedTypeName.get(
                 ClassName.get("com.github.jimsp.summer.messaging","Channel"), in, out))
           .addField(qIn,  "pubQ", Modifier.PRIVATE, Modifier.FINAL)
           .initializer("new $T<>()", ConcurrentLinkedQueue.class)
           .addField(qOut, "repQ", Modifier.PRIVATE, Modifier.FINAL)
           .initializer("new $T<>()", ConcurrentLinkedQueue.class)

           .addMethod(MethodSpec.methodBuilder("send").addAnnotation(Override.class)
                 .addModifiers(Modifier.PUBLIC).addParameter(in,"m")
                 .addStatement("pubQ.offer(m)").build())
           .addMethod(MethodSpec.methodBuilder("sendAsync").addAnnotation(Override.class)
                 .addModifiers(Modifier.PUBLIC)
                 .returns(ParameterizedTypeName.get(ClassName.get(CompletableFuture.class),ClassName.get(Void.class)))
                 .addParameter(in,"m").addStatement("pubQ.offer(m)")
                 .addStatement("return $T.completedFuture(null)",CompletableFuture.class).build())
           .addMethod(MethodSpec.methodBuilder("request").addAnnotation(Override.class)
                 .addModifiers(Modifier.PUBLIC).returns(out).addParameter(in,"m")
                 .addStatement("pubQ.offer(m)").addStatement("return repQ.poll()").build())
           .addMethod(MethodSpec.methodBuilder("requestAsync").addAnnotation(Override.class)
                 .addModifiers(Modifier.PUBLIC)
                 .returns(ParameterizedTypeName.get(ClassName.get(CompletableFuture.class),out))
                 .addParameter(in,"m").addStatement("pubQ.offer(m)")
                 .addStatement("return $T.completedFuture(repQ.poll())",CompletableFuture.class).build())
           .build();

        JavaFile.builder(pkg,impl).build().writeTo(filer);
    }

    /* -------- helpers -------- */
    private static String toUpperCamel(String s){
        if(s==null||s.isBlank()) return s;
        return Arrays.stream(s.split("[\\-_]"))
                     .filter(Predicate.not(String::isBlank))
                     .map(p->p.substring(0,1).toUpperCase()+p.substring(1).toLowerCase())
                     .collect(Collectors.joining());
    }
    private static String resolve(String v){
        if(v==null||!v.contains("${")) return v;
        Matcher m=Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}").matcher(v);
        StringBuffer sb=new StringBuffer();
        while(m.find()){
            String rep = System.getProperty(m.group(1),
                         System.getenv().getOrDefault(m.group(1),
                         m.group(2)==null ? \"\" : m.group(2)));
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb); return sb.toString();
    }
    private static String choose(String v,String d){ return v.isBlank()? d : v; }

    /* -------- cfg record -------- */
    private record ContractRaw(
            String spec,String cluster,Mode mode,
            String basePkg,String dtoPkg,String apiPkg,
            String servicePkg,String handlerPkg,String channelPkg,
            String replyChan){
        ContractRaw(Summer a){
            this(resolve(a.value()), a.cluster(), a.mode(),
                 resolve(a.basePackage()),
                 choose(resolve(a.dtoPackage()),     resolve(a.basePackage()+".dto")),
                 choose(resolve(a.apiPackage()),     resolve(a.basePackage()+".api")),
                 choose(resolve(a.servicePackage()), resolve(a.basePackage()+".service")),
                 choose(resolve(a.handlerPackage()), resolve(a.basePackage()+".handlers")),
                 choose(resolve(a.channelPackage()), resolve(a.basePackage()+".channels.generated")),
                 resolve(a.replyChannel()));
        }
    }
}
