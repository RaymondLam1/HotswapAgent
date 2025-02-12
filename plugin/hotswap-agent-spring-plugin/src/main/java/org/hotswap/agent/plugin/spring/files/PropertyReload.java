package org.hotswap.agent.plugin.spring.files;

import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.spring.core.BeanFactoryProcessor;
import org.hotswap.agent.plugin.spring.transformers.api.IResourcePropertySource;
import org.hotswap.agent.plugin.spring.utils.AnnotatedBeanDefinitionUtils;
import org.hotswap.agent.util.ReflectionHelper;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.PlaceholderConfigurerSupport;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashSet;
import java.util.Set;

public class PropertyReload {
    private static AgentLogger LOGGER = AgentLogger.getLogger(PropertyReload.class);


    public static void reloadPropertySource(DefaultListableBeanFactory beanFactory) {
        ConfigurableEnvironment environment = beanFactory.getBean(ConfigurableEnvironment.class);
        if (environment != null) {
            for (PropertySource<?> propertySource : environment.getPropertySources()) {
                if (propertySource instanceof IResourcePropertySource) {
                    try {
                        ((IResourcePropertySource) propertySource).reloadPropertySource();
                    } catch (IOException e) {
                        LOGGER.error("reload property source error", e, propertySource.getName());
                    }
                }
            }
        }

        String[] beanFactoryBeanNamesForTypes = beanFactory.getBeanNamesForType(PlaceholderConfigurerSupport.class);
        if (beanFactoryBeanNamesForTypes != null) {
            for (String beanFactoryBeanName : beanFactoryBeanNamesForTypes) {
                PlaceholderConfigurerSupport placeholderConfigurerSupport = beanFactory.getBean(beanFactoryBeanName, PlaceholderConfigurerSupport.class);
                refreshPlaceholderConfigurerSupport(beanFactory, placeholderConfigurerSupport);
            }
        }
    }

    /**
     * refresh PropertySourcesPlaceholderConfigurer or PropertyPlaceholderConfigurer.
     * The usual way is as following :
     * 1. define PropertyPlaceholderConfigurer/PropertySourcesPlaceholderConfigurer bean
     *
     * @param beanFactory
     * @param placeholderConfigurerSupport
     * @Bean public static PropertySourcesPlaceholderConfigurer properties(){
     * PropertySourcesPlaceholderConfigurer pspc
     * = new PropertySourcesPlaceholderConfigurer();
     * Resource[] resources = new ClassPathResource[ ]
     * { new ClassPathResource( "foo.properties" ) };
     * pspc.setLocations( resources );
     * pspc.setIgnoreUnresolvablePlaceholders( true );
     * return pspc;
     * }
     * 2. define PropertyPlaceholderConfigurer/PropertySourcesPlaceholderConfigurer bean in xml
     * <bean class="org.springframework.beans.factory.config.PropertyPlaceholderConfigurer">
     * <property name="locations" value="classpath:foo.properties" />
     * </bean>
     */
    private static void refreshPlaceholderConfigurerSupport(DefaultListableBeanFactory beanFactory, PlaceholderConfigurerSupport placeholderConfigurerSupport) {
        if (placeholderConfigurerSupport instanceof PropertySourcesPlaceholderConfigurer) {
            PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer = (PropertySourcesPlaceholderConfigurer) placeholderConfigurerSupport;
            // if placeholderConfigurerSupport is PropertySourcesPlaceholderConfigurer instance, it should clear and reload propertySources
            // 1. get orig propertySources
            MutablePropertySources origPropertySources = getPropertySources(propertySourcesPlaceholderConfigurer);
            // 2. clear propertySources, so it can be reinitialized
            origPropertySources.forEach(propertySource -> origPropertySources.remove(propertySource.getName()));
            ReflectionHelper.set(propertySourcesPlaceholderConfigurer, "propertySources", null);
            // 3. reinitialize propertySources. It will generate new propertySources
            propertySourcesPlaceholderConfigurer.postProcessBeanFactory(beanFactory);
            // 4. get new propertySources
            MutablePropertySources curPropertySources = getPropertySources(propertySourcesPlaceholderConfigurer);
            // 5. add new propertySources elements to orig propertySources
            curPropertySources.forEach(propertySource -> origPropertySources.addLast(propertySource));
            // 6 set orig propertySources to placeholderConfigurerSupport.
            // we should keep origPropertySources, because it is used other objects, such as StringValueResolver.
            ReflectionHelper.set(propertySourcesPlaceholderConfigurer, "propertySources", origPropertySources);
        } else if (placeholderConfigurerSupport instanceof PropertyPlaceholderConfigurer) {
            PropertyPlaceholderConfigurer propertyPlaceholderConfigurer = (PropertyPlaceholderConfigurer) placeholderConfigurerSupport;
            propertyPlaceholderConfigurer.postProcessBeanFactory(beanFactory);
        }
    }

    private static MutablePropertySources getPropertySources(PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer) {
        return (MutablePropertySources) ReflectionHelper.getNoException(propertySourcesPlaceholderConfigurer, propertySourcesPlaceholderConfigurer.getClass(), "propertySources");
    }

    /**
     * Deal with the condition:
     * 1. constructor contains @Value parameter
     * 2. @Bean method contains @Value parameter
     *
     * @param beanFactory
     * @return
     */
    public static Set<String> getContainValueAnnotationBeans(DefaultListableBeanFactory beanFactory) {
        Set<String> needRecreateBeans = new HashSet<>();
        // resolve constructor arguments
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
            if (beanDefinition instanceof AnnotatedBeanDefinition) {
                if (beanDefinition instanceof RootBeanDefinition) {
                    RootBeanDefinition currentBeanDefinition = (RootBeanDefinition) beanDefinition;
                    if (containValueAnnotationInMethod(beanFactory, currentBeanDefinition)) {
                        needRecreateBeans.add(beanName);
                    }
                } else if (beanDefinition instanceof GenericBeanDefinition) {
                    GenericBeanDefinition currentBeanDefinition = (GenericBeanDefinition) beanDefinition;
                    AnnotatedBeanDefinition annotatedBeanDefinition = (AnnotatedBeanDefinition) currentBeanDefinition;
                    if (AnnotatedBeanDefinitionUtils.getFactoryMethodMetadata(annotatedBeanDefinition) != null) {
                        continue;
                    }
                    if (BeanFactoryProcessor.needReloadOnConstructor(beanFactory, currentBeanDefinition, beanName, constructors -> checkConstructorContainsValueAnnotation(constructors))) {
                        needRecreateBeans.add(beanName);
                    }
                }
            }
        }
        return needRecreateBeans;
    }

    private static boolean containValueAnnotationInMethod(DefaultListableBeanFactory beanFactory, RootBeanDefinition currentBeanDefinition) {
        if (currentBeanDefinition.getFactoryMethodName() != null && currentBeanDefinition.getFactoryBeanName() != null) {
            Method method = currentBeanDefinition.getResolvedFactoryMethod();
            if (method == null) {
                Object factoryBean = beanFactory.getBean(currentBeanDefinition.getFactoryBeanName());
                Class factoryClass = ClassUtils.getUserClass(factoryBean.getClass());
                Method[] methods = getCandidateMethods(factoryClass, currentBeanDefinition);
                for (Method m : methods) {
                    if (!Modifier.isStatic(m.getModifiers()) && currentBeanDefinition.isFactoryMethod(m) &&
                            m.getParameterCount() != 0 && AnnotatedBeanDefinitionUtils.containValueAnnotation(m.getParameterAnnotations())) {
                        return true;
                    }
                }
            } else if (method.getParameterCount() != 0) {
                // @Bean method contains @Value parameter
                if (AnnotatedBeanDefinitionUtils.containValueAnnotation(method.getParameterAnnotations())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
        if (System.getSecurityManager() != null) {
            return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
                    (mbd.isNonPublicAccessAllowed() ?
                            ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
        } else {
            return (mbd.isNonPublicAccessAllowed() ?
                    ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
        }
    }

    private static boolean checkConstructorContainsValueAnnotation(Constructor<?>[] constructors) {
        for (Constructor constructor : constructors) {
            if (constructor.getParameterCount() != 0 && AnnotatedBeanDefinitionUtils.containValueAnnotation(constructor.getParameterAnnotations())) {
                return true;
            }
        }
        return false;
    }
}
