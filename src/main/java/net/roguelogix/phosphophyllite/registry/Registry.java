package net.roguelogix.phosphophyllite.registry;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.data.worldgen.features.OreFeatures;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Material;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.world.BiomeLoadingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.forgespi.language.ModFileScanData;
import net.roguelogix.phosphophyllite.config.ConfigManager;
import net.roguelogix.phosphophyllite.threading.WorkQueue;
import net.roguelogix.phosphophyllite.util.VanillaFeatureWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class Registry {
    
    private static final Logger LOGGER = LogManager.getLogger("Phosphophyllite/Registry");
    
    private final WorkQueue blockRegistrationQueue = new WorkQueue();
    private RegistryEvent.Register<Block> blockRegistryEvent;
    
    private final WorkQueue itemRegistrationQueue = new WorkQueue();
    private RegistryEvent.Register<Item> itemRegistryEvent;
    private final CreativeModeTab itemGroup;
    private Item itemGroupItem = Items.STONE;
    
    private final WorkQueue fluidRegistrationQueue = new WorkQueue();
    private RegistryEvent.Register<Fluid> fluidRegistryEvent;
    
    private final WorkQueue containerRegistrationQueue = new WorkQueue();
    private RegistryEvent.Register<MenuType<?>> containerRegistryEvent;
    
    private final WorkQueue tileRegistrationQueue = new WorkQueue();
    private RegistryEvent.Register<BlockEntityType<?>> tileRegistryEvent;
    private final Map<Class<?>, LinkedList<Block>> tileBlocks = new HashMap<>();
    
    private final WorkQueue clientSetupQueue = new WorkQueue();
    private FMLClientSetupEvent clientSetupEvent;
    
    private final WorkQueue commonSetupQueue = new WorkQueue();
    private FMLCommonSetupEvent commonSetupEvent;
    
    private final ArrayList<Runnable> biomeLoadingEventHandlers = new ArrayList<>();
    private BiomeLoadingEvent biomeLoadingEvent;
    
    private final Map<String, AnnotationHandler> annotationMap = new HashMap<>();
    
    {
        annotationMap.put(RegisterBlock.class.getName(), this::registerBlockAnnotation);
        annotationMap.put(RegisterItem.class.getName(), this::registerItemAnnotation);
        annotationMap.put(RegisterFluid.class.getName(), this::registerFluidAnnotation);
        annotationMap.put(RegisterContainer.class.getName(), this::registerContainerAnnotation);
        //noinspection removal
        annotationMap.put(RegisterTileEntity.class.getName(), this::registerTileEntityAnnotation);
        annotationMap.put(RegisterTile.class.getName(), this::registerTileAnnotation);
        annotationMap.put(RegisterOre.class.getName(), this::registerWorldGenAnnotation);
//        annotationMap.put(RegisterConfig.class.getName(), this::registerConfigAnnotation);
//        annotationMap.put(OnModLoad.class.getName(), this::onModLoadAnnotation);
    }
    
    public Registry() {
        String callerClass = new Exception().getStackTrace()[1].getClassName();
        String callerPackage = callerClass.substring(0, callerClass.lastIndexOf("."));
        String modNamespace = callerPackage.substring(callerPackage.lastIndexOf(".") + 1);
        ModFileScanData modFileScanData = FMLLoader.getLoadingModList().getModFileById(modNamespace).getFile().getScanResult();
        
        itemGroup = new CreativeModeTab(modNamespace) {
            @Override
            @Nonnull
            public ItemStack makeIcon() {
                return new ItemStack(itemGroupItem);
            }
            
            @Override
            public void fillItemList(@Nonnull NonNullList<ItemStack> items) {
                super.fillItemList(items);
                items.sort((o1, o2) -> o1.getDisplayName().getString().compareToIgnoreCase(o2.getDisplayName().getString()));
            }
        };
        
        // these two are special cases that need to be handled first
        // in case anything needs config options during static construction
        handleAnnotationType(modFileScanData, callerPackage, modNamespace, RegisterConfig.class.getName(), this::registerConfigAnnotation);
        // this is used for module registration, which need to happen before block registration
        handleAnnotationType(modFileScanData, callerPackage, modNamespace, OnModLoad.class.getName(), this::onModLoadAnnotation);
        
        
        for (ModFileScanData.AnnotationData annotation : modFileScanData.getAnnotations()) {
            AnnotationHandler handler = annotationMap.get(annotation.annotationType().getClassName());
            if (handler == null) {
                // not an annotation i handle
                continue;
            }
            String className = annotation.clazz().getClassName();
            if (className.startsWith(callerPackage)) {
                try {
                    Class<?> clazz = Registry.class.getClassLoader().loadClass(className);
                    if (clazz.isAnnotationPresent(ClientOnly.class) && !FMLEnvironment.dist.isClient()) {
                        continue;
                    }
                    if (clazz.isAnnotationPresent(SideOnly.class)) {
                        var sideOnly = clazz.getAnnotation(SideOnly.class);
                        if (sideOnly.value() != FMLEnvironment.dist) {
                            continue;
                        }
                    }
                    // class loaded, so, pass it off to the handler
                    handler.run(modNamespace, clazz, annotation.memberName());
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
        }
        
        
        IEventBus ModBus = FMLJavaModLoadingContext.get().getModEventBus();
        
        ModBus.addGenericListener(Block.class, this::blockRegistration);
        ModBus.addGenericListener(Item.class, this::itemRegistration);
        ModBus.addGenericListener(Fluid.class, this::fluidRegistration);
        ModBus.addGenericListener(MenuType.class, this::containerRegistration);
        ModBus.addGenericListener(BlockEntityType.class, this::tileEntityRegistration);
        
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, this::biomeLoadingEventHandler);
        ModBus.addListener(this::commonSetupEventHandler);
        
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModBus.addListener(this::clientSetupEventHandler);
        }
    }
    
    private static void handleAnnotationType(ModFileScanData modFileScanData, String callerPackage, String modNamespace, String name, AnnotationHandler handler) {
        for (ModFileScanData.AnnotationData annotation : modFileScanData.getAnnotations()) {
            if (!annotation.annotationType().getClassName().equals(name)) {
                // not the annotation handled here
                continue;
            }
            String className = annotation.clazz().getClassName();
            if (className.startsWith(callerPackage)) {
                try {
                    Class<?> clazz = Registry.class.getClassLoader().loadClass(className);
                    if (clazz.isAnnotationPresent(ClientOnly.class) && !FMLEnvironment.dist.isClient()) {
                        continue;
                    }
                    if (clazz.isAnnotationPresent(SideOnly.class)) {
                        var sideOnly = clazz.getAnnotation(SideOnly.class);
                        if (sideOnly.value() != FMLEnvironment.dist) {
                            continue;
                        }
                    }
                    // class loaded, so, pass it off to the handler
                    handler.run(modNamespace, clazz, annotation.memberName());
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
        }
    }
    
    private void blockRegistration(RegistryEvent.Register<Block> event) {
        blockRegistryEvent = event;
        blockRegistrationQueue.runAll();
        blockRegistryEvent = null;
    }
    
    private void itemRegistration(RegistryEvent.Register<Item> event) {
        itemRegistryEvent = event;
        itemRegistrationQueue.runAll();
        itemRegistryEvent = null;
    }
    
    private void fluidRegistration(RegistryEvent.Register<Fluid> event) {
        fluidRegistryEvent = event;
        fluidRegistrationQueue.runAll();
        fluidRegistryEvent = null;
    }
    
    private void containerRegistration(RegistryEvent.Register<MenuType<?>> containerTypeRegistryEvent) {
        containerRegistryEvent = containerTypeRegistryEvent;
        containerRegistrationQueue.runAll();
        containerRegistryEvent = null;
    }
    
    private void tileEntityRegistration(RegistryEvent.Register<BlockEntityType<?>> tileEntityTypeRegister) {
        tileRegistryEvent = tileEntityTypeRegister;
        tileRegistrationQueue.runAll();
        tileRegistryEvent = null;
    }
    
    private void clientSetupEventHandler(FMLClientSetupEvent event) {
        clientSetupEvent = event;
        clientSetupQueue.runAll();
        clientSetupEvent = null;
    }
    
    private void commonSetupEventHandler(FMLCommonSetupEvent event) {
        commonSetupEvent = event;
        commonSetupQueue.runAll();
        commonSetupEvent = null;
    }
    
    private void biomeLoadingEventHandler(BiomeLoadingEvent event) {
        biomeLoadingEvent = event;
        biomeLoadingEventHandlers.forEach(Runnable::run);
        biomeLoadingEvent = null;
    }
    
    private interface AnnotationHandler {
        void run(final String modNamespace, final Class<?> clazz, final String memberName);
    }
    
    private void registerBlockAnnotation(final String modNamespace, final Class<?> blockClazz, final String memberName) {
        if (blockClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            return;
        }
        
        blockRegistrationQueue.enqueue(() -> {
            final Block block;
            final RegisterBlock annotation;
            try {
                final Field field = blockClazz.getDeclaredField(memberName);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    return;
                }
                field.setAccessible(true);
                block = (Block) field.get(null);
                annotation = field.getAnnotation(RegisterBlock.class);
                
                if (!Modifier.isFinal(field.getModifiers())) {
                    LOGGER.warn("Non-final block instance variable " + memberName + " in " + blockClazz.getSimpleName());
                }
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find block field for block " + memberName + " in " + blockClazz.getSimpleName());
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access block field for block " + memberName + " in " + blockClazz.getSimpleName());
                return;
            }
            
            if (block == null) {
                LOGGER.warn("Null block instance variable " + memberName + " in " + blockClazz.getSimpleName());
                return;
            }
            
            String modid = annotation.modid();
            if (modid.equals("")) {
                modid = modNamespace;
            }
            String name = annotation.name();
            if (modid.equals("")) {
                LOGGER.error("Unable to register block without a name from class " + blockClazz.getSimpleName());
                return;
            }
            
            if (!Block.class.isAssignableFrom(blockClazz)) {
                LOGGER.error("Attempt to register block from class not extended from Block. " + blockClazz.getSimpleName());
                return;
            }
            
            
            final String registryName = modid + ":" + name;
            
            
            Constructor<?> constructor;
            try {
                constructor = blockClazz.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                LOGGER.error("Failed to find default constructor to create instance of " + blockClazz.getSimpleName());
                return;
            }
            constructor.setAccessible(true);
            
            if (annotation.tileEntityClass() != BlockEntity.class) {
                tileBlocks.computeIfAbsent(annotation.tileEntityClass(), k -> new LinkedList<>()).add(block);
            }
            
            block.setRegistryName(registryName);
            blockRegistryEvent.getRegistry().register(block);
            
            if (FMLEnvironment.dist.isClient()) {
                clientSetupQueue.enqueue(() -> {
                    RenderType renderType = null;
                    for (Method declaredMethod : blockClazz.getDeclaredMethods()) {
                        if (declaredMethod.isAnnotationPresent(RegisterBlock.RenderLayer.class)) {
                            if (Modifier.isStatic(declaredMethod.getModifiers())) {
                                LOGGER.error("Cannot call static render layer method " + declaredMethod.getName() + " in " + blockClazz.getSimpleName());
                                continue;
                            }
                            if (!RenderType.class.isAssignableFrom(declaredMethod.getReturnType())) {
                                LOGGER.error("RenderLayer annotated method " + declaredMethod.getName() + " in " + blockClazz.getSimpleName() + " does not return RenderType");
                                continue;
                            }
                            if (declaredMethod.getParameterCount() != 0) {
                                LOGGER.error("RenderLayer annotated method " + declaredMethod.getName() + " in " + blockClazz.getSimpleName() + " requires arguments");
                                continue;
                            }
                            if (renderType != null) {
                                LOGGER.error("Duplicate RenderLayer methods in " + blockClazz.getSimpleName());
                                continue;
                            }
                            declaredMethod.setAccessible(true);
                            try {
                                Object obj = declaredMethod.invoke(block);
                                renderType = (RenderType) obj;
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                e.printStackTrace();
                                continue;
                            }
                            final RenderType finalRenderType = renderType;
                            // parallel dispatch, and non-synchronized
                            clientSetupEvent.enqueueWork(() -> ItemBlockRenderTypes.setRenderLayer(block, finalRenderType));
                        }
                    }
                });
            }
            
            if (annotation.registerItem()) {
                
                boolean creativeTabBlock = blockClazz.isAnnotationPresent(CreativeTabBlock.class);
                itemRegistrationQueue.enqueue(() -> {
                    //noinspection ConstantConditions
                    var item = new BlockItem(block, new Item.Properties().tab(annotation.creativeTab() ? itemGroup : null /* its fine */)).setRegistryName(registryName);
                    itemRegistryEvent.getRegistry().register(item);
                    if (creativeTabBlock) {
                        itemGroupItem = item;
                    }
                });
            }
        });
    }
    
    private void registerItemAnnotation(String modNamespace, Class<?> itemClazz, final String memberName) {
        if (itemClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            return;
        }
        
        itemRegistrationQueue.enqueue(() -> {
            
            assert itemClazz.isAnnotationPresent(RegisterItem.class);
            
            final RegisterItem annotation = itemClazz.getAnnotation(RegisterItem.class);
            
            String modid = annotation.modid();
            if (modid.equals("")) {
                modid = modNamespace;
            }
            String name = annotation.name();
            if (modid.equals("")) {
                LOGGER.error("Unable to register item without a name");
                return;
            }
            
            
            if (!Item.class.isAssignableFrom(itemClazz)) {
                LOGGER.error("Attempt to register item from class not extended from Item");
                return;
            }
            
            final String registryName = modid + ":" + name;
            
            Constructor<?> constructor;
            try {
                constructor = itemClazz.getDeclaredConstructor(Item.Properties.class);
            } catch (NoSuchMethodException e) {
                LOGGER.error("Failed to find constructor to create instance of " + itemClazz.getSimpleName());
                return;
            }
            constructor.setAccessible(true);
            
            Item item;
            try {
                //noinspection ConstantConditions
                item = (Item) constructor.newInstance(new Item.Properties().tab(annotation.creativeTab() ? itemGroup : null /* its fine */));
            } catch (InstantiationException | InvocationTargetException | IllegalAccessException | ClassCastException e) {
                LOGGER.error("Exception caught when instantiating instance of " + itemClazz.getSimpleName());
                e.printStackTrace();
                return;
            }
            
            for (Field declaredField : itemClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterItem.Instance.class)) {
                    if (!declaredField.getType().isAssignableFrom(itemClazz)) {
                        LOGGER.error("Unassignable instance variable " + declaredField.getName() + " in " + itemClazz.getSimpleName());
                        continue;
                    }
                    if (!Modifier.isStatic(declaredField.getModifiers())) {
                        LOGGER.error("Cannot set non-static instance variable " + declaredField.getName() + " in " + itemClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        declaredField.set(null, item);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            item.setRegistryName(registryName);
            itemRegistryEvent.getRegistry().register(item);
        });
    }
    
    private void registerFluidAnnotation(String modNamespace, Class<?> fluidClazz, final String memberName) {
        if (fluidClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            return;
        }
        
        blockRegistrationQueue.enqueue(() -> {
            
            assert fluidClazz.isAnnotationPresent(RegisterFluid.class);
            
            final RegisterFluid annotation = fluidClazz.getAnnotation(RegisterFluid.class);
            
            String modid = annotation.modid().equals("") ? modNamespace : annotation.modid();
            String name = annotation.name();
            if (modid.equals("")) {
                LOGGER.error("Unable to register fluid without a name");
                return;
            }
            
            
            if (!ForgeFlowingFluid.class.isAssignableFrom(fluidClazz)) {
                LOGGER.error("Attempt to register fluid from class not extended from PhosphophylliteFluid");
                return;
            }
            
            final String baseRegistryName = modid + ":" + name;
            
            PhosphophylliteFluid[] fluids = new PhosphophylliteFluid[2];
            Item[] bucketArray = new Item[1];
            LiquidBlock[] blockArray = new LiquidBlock[1];
            
            Constructor<?> constructor;
            try {
                constructor = fluidClazz.getDeclaredConstructor(ForgeFlowingFluid.Properties.class);
            } catch (NoSuchMethodException e) {
                LOGGER.error("Failed to find constructor to create instance of " + fluidClazz.getSimpleName());
                return;
            }
            
            Supplier<? extends PhosphophylliteFluid> stillSupplier = () -> fluids[0];
            Supplier<? extends PhosphophylliteFluid> flowingSupplier = () -> fluids[1];
            FluidAttributes.Builder attributes = FluidAttributes.builder(new ResourceLocation(modid, "fluid/" + name + "_still"), new ResourceLocation(modid, "fluid/" + name + "_flowing"));
//            attributes.overlay(new ResourceLocation(modid, "fluid/" + name + "_overlay"));
            attributes.color(annotation.color());
            ForgeFlowingFluid.Properties properties = new ForgeFlowingFluid.Properties(stillSupplier, flowingSupplier, attributes);
            if (annotation.registerBucket()) {
                properties.bucket(() -> bucketArray[0]);
            }
            properties.block(() -> blockArray[0]);
            
            PhosphophylliteFluid stillInstance;
            PhosphophylliteFluid flowingInstance;
            
            try {
                stillInstance = (PhosphophylliteFluid) constructor.newInstance(properties);
                flowingInstance = (PhosphophylliteFluid) constructor.newInstance(properties);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                LOGGER.error("Exception caught when instantiating instance of " + fluidClazz.getSimpleName());
                e.printStackTrace();
                return;
            }
            
            stillInstance.isSource = true;
            
            fluids[0] = stillInstance;
            fluids[1] = flowingInstance;
            blockArray[0] = new LiquidBlock(stillInstance, Block.Properties.of(Material.WATER).noCollission().explosionResistance(100.0F).noDrops());
            
            for (Field declaredField : fluidClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterFluid.Instance.class)) {
                    if (!declaredField.getType().isAssignableFrom(fluidClazz)) {
                        LOGGER.error("Unassignable instance variable " + declaredField.getName() + " in " + fluidClazz.getSimpleName());
                        continue;
                    }
                    if (!Modifier.isStatic(declaredField.getModifiers())) {
                        LOGGER.error("Cannot set non-static instance variable " + declaredField.getName() + " in " + fluidClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        declaredField.set(null, stillInstance);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            blockArray[0].setRegistryName(baseRegistryName);
            blockRegistryEvent.getRegistry().register(blockArray[0]);
            
            fluidRegistrationQueue.enqueue(() -> {
                PhosphophylliteFluid still = fluids[0];
                PhosphophylliteFluid flowing = fluids[1];
                if (still == null || flowing == null) {
                    return;
                }
                
                still.setRegistryName(baseRegistryName);
                flowing.setRegistryName(baseRegistryName + "_flowing");
                
                fluidRegistryEvent.getRegistry().register(still);
                fluidRegistryEvent.getRegistry().register(flowing);
            });
            
            if (annotation.registerBucket()) {
                itemRegistrationQueue.enqueue(() -> {
                    BucketItem bucket = new BucketItem(() -> fluids[0], new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1).tab(itemGroup));
                    bucketArray[0] = bucket;
                    bucket.setRegistryName(baseRegistryName + "_bucket");
                    itemRegistryEvent.getRegistry().register(bucket);
                });
            }
        });
    }
    
    private void registerContainerAnnotation(String modNamespace, Class<?> containerClazz, final String memberName) {
        if (containerClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            return;
        }
        
        assert containerClazz.isAnnotationPresent(RegisterContainer.class);
        
        final RegisterContainer annotation = containerClazz.getAnnotation(RegisterContainer.class);
        
        String modid = annotation.modid();
        if (modid.equals("")) {
            modid = modNamespace;
        }
        String name = annotation.name();
        if (modid.equals("")) {
            LOGGER.error("Unable to register container without a name");
            return;
        }
        
        
        if (!AbstractContainerMenu.class.isAssignableFrom(containerClazz)) {
            LOGGER.error("Attempt to register container from class not extended from Container");
            return;
        }
        
        final String registryName = modid + ":" + name;
        
        MenuType<?>[] containerTypeArray = new MenuType[1];
        
        containerRegistrationQueue.enqueue(() -> {
            ContainerSupplier supplier = null;
            for (Field declaredField : containerClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterContainer.Supplier.class)) {
                    int modifiers = declaredField.getModifiers();
                    if (!Modifier.isStatic(modifiers)) {
                        LOGGER.error("Cannot access non-static container supplier " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        return;
                    }
                    if (!Modifier.isFinal(modifiers)) {
                        LOGGER.warn("Container supplier " + declaredField.getName() + " not final in" + containerClazz.getSimpleName());
                    }
                    if (!ContainerSupplier.class.isAssignableFrom(declaredField.getType())) {
                        LOGGER.error("Supplier annotation found on non-ContainerSupplier field " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    if (supplier != null) {
                        LOGGER.error("Duplicate suppliers for container " + containerClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        supplier = (ContainerSupplier) declaredField.get(null);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                        continue;
                    }
                    if (supplier == null) {
                        LOGGER.error("Container supplier field " + declaredField.getName() + " null in " + containerClazz.getSimpleName());
                    }
                }
            }
            if (supplier == null) {
                LOGGER.error("No supplier found for container " + containerClazz.getSimpleName());
                return;
            }
            
            ContainerSupplier finalSupplier = supplier;
            containerTypeArray[0] = IForgeMenuType.create((windowId, playerInventory, data) -> finalSupplier.create(windowId, data.readBlockPos(), playerInventory.player));
            
            for (Field declaredField : containerClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterContainer.Type.class)) {
                    if (!declaredField.getType().isAssignableFrom(MenuType.class)) {
                        LOGGER.error("Unassignable type variable " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    if (!Modifier.isStatic(declaredField.getModifiers())) {
                        LOGGER.error("Cannot set non-static type variable " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        declaredField.set(null, containerTypeArray[0]);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            MenuType<?> type = containerTypeArray[0];
            if (type == null) {
                return;
            }
            type.setRegistryName(registryName);
            containerRegistryEvent.getRegistry().register(type);
        });
    }
    
    @SuppressWarnings("removal")
    @Deprecated(forRemoval = true)
    private void registerTileEntityAnnotation(String modNamespace, Class<?> tileClazz, final String memberName) {
        if (tileClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            return;
        }
        
        assert tileClazz.isAnnotationPresent(RegisterTileEntity.class);
        
        tileRegistrationQueue.enqueue(() -> {
            final RegisterTileEntity annotation = tileClazz.getAnnotation(RegisterTileEntity.class);
            
            String modid = annotation.modid();
            if (modid.equals("")) {
                modid = modNamespace;
            }
            String name = annotation.name();
            if (modid.equals("")) {
                LOGGER.error("Unable to register tile type without a name");
                return;
            }
            
            if (!BlockEntity.class.isAssignableFrom(tileClazz)) {
                LOGGER.error("Attempt to register tile type from class not extended from TileEntity");
                return;
            }
            
            final String registryName = modid + ":" + name;
            
            BlockEntityType.BlockEntitySupplier<?> supplier = null;
            
            for (Field declaredField : tileClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterTileEntity.Supplier.class)) {
                    int modifiers = declaredField.getModifiers();
                    if (!Modifier.isStatic(modifiers)) {
                        LOGGER.error("Cannot access non-static tile supplier " + declaredField.getName() + " in " + tileClazz.getSimpleName());
                        return;
                    }
                    if (!Modifier.isFinal(modifiers)) {
                        LOGGER.warn("Tile supplier " + declaredField.getName() + " not final in" + tileClazz.getSimpleName());
                    }
                    if (!BlockEntityType.BlockEntitySupplier.class.isAssignableFrom(declaredField.getType())) {
                        LOGGER.error("Supplier annotation found on non-TileSupplier field " + declaredField.getName() + " in " + tileClazz.getSimpleName());
                        continue;
                    }
                    if (supplier != null) {
                        LOGGER.error("Duplicate suppliers for tile " + tileClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        supplier = (BlockEntityType.BlockEntitySupplier<?>) declaredField.get(null);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                        continue;
                    }
                    if (supplier == null) {
                        LOGGER.error("Tile supplier field " + declaredField.getName() + " null in " + tileClazz.getSimpleName());
                    }
                }
            }
            
            if (supplier == null) {
                // fall back to using the constructor via LambdaMetaFactory, if the correct one exists
                // its not quite as fast, but its easier, and *meh*
                
                try {
                    var lookup = MethodHandles.lookup();
                    var methodHandle = lookup.findConstructor(tileClazz, MethodType.methodType(void.class, new Class<?>[]{BlockPos.class, BlockState.class}));
                    var callSite = LambdaMetafactory.metafactory(
                            lookup,
                            "apply",
                            MethodType.methodType(BiFunction.class),
                            methodHandle.type().generic(),
                            methodHandle,
                            methodHandle.type()
                    );
                    @SuppressWarnings("unchecked")
                    BiFunction<BlockPos, BlockState, BlockEntity> tileSupplier = (BiFunction<BlockPos, BlockState, BlockEntity>) callSite.getTarget().invokeExact();
                    //noinspection NullableProblems
                    supplier = tileSupplier::apply;
                } catch (NoSuchMethodException ignored) {
                    // if it doesn't exist, that's handled below
                    LOGGER.error("Unable to find constructor for " + tileClazz.getSimpleName());
                } catch (LambdaConversionException e) {
                    LOGGER.error("LambdaConversionException creating tile supplier for for " + tileClazz.getSimpleName());
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    LOGGER.error("Unable to access constructor for " + tileClazz.getSimpleName());
                } catch (Throwable throwable) {
                    LOGGER.error("Unknown error occurred attempting to create supplier for " + tileClazz.getSimpleName());
                    throwable.printStackTrace();
                }
            }
            
            if (supplier == null) {
                throw new IllegalStateException("No supplier found for tile " + tileClazz.getSimpleName());
            }
            
            
            LinkedList<Block> blocks = tileBlocks.remove(tileClazz);
            
            if (blocks == null) {
                return;
            }
            
            // fuck you java, its the correct size here
            @SuppressWarnings({"ConstantConditions", "ToArrayCallWithZeroLengthArrayArgument"})
            BlockEntityType<?> type = BlockEntityType.Builder.of(supplier, blocks.toArray(new Block[blocks.size()])).build(null);
            
            type.setRegistryName(registryName);
            
            boolean typeSet = false;
            for (Field declaredField : tileClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterTileEntity.Type.class)) {
                    if (!declaredField.getType().isAssignableFrom(BlockEntityType.class)) {
                        LOGGER.error("Unassignable type variable " + declaredField.getName() + " in " + tileClazz.getSimpleName());
                        continue;
                    }
                    if (!Modifier.isStatic(declaredField.getModifiers())) {
                        LOGGER.error("Cannot set non-static type variable " + declaredField.getName() + " in " + tileClazz.getSimpleName());
                        continue;
                    }
                    if (typeSet) {
                        LOGGER.warn("Duplicate TileEntityType fields in " + tileClazz.getSimpleName());
                    }
                    declaredField.setAccessible(true);
                    try {
                        declaredField.set(null, type);
                        typeSet = true;
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            if (!typeSet) {
                throw new IllegalStateException("Tile entity type unable to be saved for " + tileClazz.getSimpleName());
            }
            tileRegistryEvent.getRegistry().register(type);
        });
    }
    
    private static final Field tileProducerTYPEField;
    
    static {
        try {
            tileProducerTYPEField = RegisterTile.Producer.class.getDeclaredField("TYPE");
            tileProducerTYPEField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
    
    private void registerTileAnnotation(String modNamespace, Class<?> declaringClass, final String memberName) {
        tileRegistrationQueue.enqueue(() -> {
    
            final Field field;
            final RegisterTile annotation;
            final RegisterTile.Producer<?> producer;
    
            try {
                field = declaringClass.getDeclaredField(memberName);
                if (!field.isAnnotationPresent(RegisterTile.class)) {
                    LOGGER.error("Schrodinger's annotation on field " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                annotation = field.getAnnotation(RegisterTile.class);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    return;
                }
                field.setAccessible(true);
                var producerObject = field.get(null);
                if (producerObject == null) {
                    LOGGER.error("Null supplier for tile field " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                if (producerObject.getClass() != RegisterTile.Producer.class) {
                    LOGGER.error("Attempt to register non-TileProducer BlockEntitySupplier " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                producer = (RegisterTile.Producer<?>) producerObject;
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find supplier field for tile " + memberName + " in " + declaringClass.getSimpleName());
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access supplier field for tile " + memberName + " in " + declaringClass.getSimpleName());
                return;
            }
            
            String modid = annotation.modid();
            if (modid.equals("")) {
                modid = modNamespace;
            }
            String name = annotation.name();
            if (modid.equals("")) {
                LOGGER.error("Unable to register tile without a name from " + memberName + " in " + declaringClass.getSimpleName());
                return;
            }
            final String registryName = modid + ":" + name;
    
            // this is safe, surely
            // should actually be, otherwise previous checks should have errored
            Class<?> tileClass = (Class<?>) ((ParameterizedType)field.getGenericType()).getActualTypeArguments()[0];
    
            LinkedList<Block> blocks = tileBlocks.remove(tileClass);
    
            if (blocks == null) {
                return;
            }
    
            // fuck you java, its the correct size here
            @SuppressWarnings({"ConstantConditions", "ToArrayCallWithZeroLengthArrayArgument"})
            BlockEntityType<?> type = BlockEntityType.Builder.of(producer, blocks.toArray(new Block[blocks.size()])).build(null);
    
            type.setRegistryName(registryName);
    
            try {
                tileProducerTYPEField.set(producer, type);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Tile entity type unable to be saved for " + memberName + " in " + declaringClass.getSimpleName());
            }
    
            tileRegistryEvent.getRegistry().register(type);
        });
    }
    
    private void registerWorldGenAnnotation(String modNamespace, Class<?> oreClazz, final String memberName) {
        commonSetupQueue.enqueue(() -> {
            final Block oreInstance;
            try {
                final Field field = oreClazz.getDeclaredField(memberName);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    return;
                }
                field.setAccessible(true);
                oreInstance = (Block) field.get(null);
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find block field for block " + memberName);
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access block field for block " + memberName);
                return;
            }
            
            final ResourceLocation resourceLocation = oreInstance.getRegistryName();
            
            
            if (!(oreInstance instanceof IPhosphophylliteOre oreInfo)) {
                LOGGER.error("Attempt to register non-IPhosphophylliteOre block for world generation");
                return;
            }
            
            final RuleTest fillerBlock = oreInfo.isNetherOre() ? OreFeatures.NETHER_ORE_REPLACEABLES : OreFeatures.STONE_ORE_REPLACEABLES;
            
            final ConfiguredFeature<?, ?> feature = Feature.ORE.configured(new OreConfiguration(fillerBlock, oreInstance.defaultBlockState(), oreInfo.size()));
            
            boolean doSpawn = oreInfo.doSpawn();
            
            final var wrapper = new VanillaFeatureWrapper<>(feature, () -> doSpawn);
            
            final HashSet<String> spawnBiomes = new HashSet<>(Arrays.asList(oreInfo.spawnBiomes()));
            commonSetupEvent.enqueueWork(() -> net.minecraft.core.Registry.register(net.minecraft.core.RegistryAccess.builtin().registryOrThrow(net.minecraft.core.Registry.CONFIGURED_FEATURE_REGISTRY), resourceLocation, wrapper));
            
            final var placed = wrapper.placed(OrePlacements.orePlacement(
                    CountPlacement.of(oreInfo.count()),
                    HeightRangePlacement.uniform(VerticalAnchor.absolute(oreInfo.minLevel()), VerticalAnchor.absolute(oreInfo.maxLevel()))
            ));
            
            biomeLoadingEventHandlers.add(() -> {
                if ((biomeLoadingEvent.getCategory() == Biome.BiomeCategory.NETHER) != oreInfo.isNetherOre()) {
                    return;
                }
                if (spawnBiomes.size() > 0) {
                    if (!spawnBiomes.contains(biomeLoadingEvent.getName().toString())) {
                        return;
                    }
                }
                
                biomeLoadingEvent.getGeneration().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES).add(() -> placed);
            });
        });
    }
    
    private void registerConfigAnnotation(String modNamespace, Class<?> configClazz, final String memberName) {
        try {
            Field field = configClazz.getDeclaredField(memberName);
            if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                return;
            }
            ConfigManager.registerConfig(field, modNamespace);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }
    
    private void onModLoadAnnotation(String modNamespace, Class<?> modLoadClazz, final String memberName) {
        try {
            Method method = modLoadClazz.getDeclaredMethod(memberName.substring(0, memberName.indexOf('(')));
            if (method.isAnnotationPresent(IgnoreRegistration.class)) {
                return;
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                LOGGER.error("Cannot call non-static @OnModLoad method " + method.getName() + " in " + modLoadClazz.getSimpleName());
                return;
            }
            
            if (method.getParameterCount() != 0) {
                LOGGER.error("Cannot call @OnModLoad method with parameters " + method.getName() + " in " + modLoadClazz.getSimpleName());
                return;
            }
            
            method.setAccessible(true);
            method.invoke(null);
            
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            e.printStackTrace();
        } catch (RuntimeException e) {
            LOGGER.warn(modLoadClazz.getName());
            e.printStackTrace();
        }
    }
}
