package com.minecolonies.coremod.entity.ai.basic;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.buildings.IBuildingWorker;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.Tool;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.player.IPlayerRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.retrying.IRetryingRequestResolver;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.ai.pathfinding.IWalkToProxy;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.util.*;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.api.util.constant.IToolType;
import com.minecolonies.api.util.constant.ToolType;
import com.minecolonies.api.util.constant.TypeConstants;
import com.minecolonies.coremod.colony.buildings.AbstractBuildingWorker;
import com.minecolonies.coremod.colony.interactionhandling.PosBasedInteractionResponseHandler;
import com.minecolonies.coremod.colony.interactionhandling.RequestBasedInteractionResponseHandler;
import com.minecolonies.coremod.colony.interactionhandling.StandardInteractionResponseHandler;
import com.minecolonies.coremod.colony.jobs.AbstractJob;
import com.minecolonies.coremod.colony.jobs.JobDeliveryman;
import com.minecolonies.coremod.entity.ai.minimal.EntityAIStatePausedHandler;
import com.minecolonies.coremod.entity.pathfinding.EntityCitizenWalkToProxy;
import com.minecolonies.coremod.util.WorkerUtil;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import com.minecolonies.api.colony.interactionhandling.TranslationTextComponent;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.util.constant.BuildingConstants.MAX_PRIO;
import static com.minecolonies.api.util.constant.CitizenConstants.*;
import static com.minecolonies.api.util.constant.Suppression.RAWTYPES;
import static com.minecolonies.api.util.constant.ToolLevelConstants.TOOL_LEVEL_WOOD_OR_GOLD;
import static com.minecolonies.api.util.constant.TranslationConstants.*;
import static net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY;

/**
 * This class provides basic ai functionality.
 *
 * @param <J> The job this ai has to fulfil
 */
public abstract class AbstractEntityAIBasic<J extends AbstractJob> extends AbstractAISkeleton<J>
{
    /**
     * The standard delay after each terminated action.
     */
    protected static final int STANDARD_DELAY = 5;

    /**
     * The standard delay after each terminated action.
     */
    protected static final int REQUEST_DELAY = TICKS_20 * 3;

    /**
     * The block the ai is currently working at or wants to work.
     */
    @Nullable
    protected BlockPos currentWorkingLocation = null;

    /**
     * The block the ai is currently standing at or wants to stand.
     */
    @Nullable
    protected BlockPos currentStandingLocation = null;

    /**
     * The time in ticks until the next action is made.
     */
    private int delay = 0;

    /**
     * If we have waited one delay.
     */
    private boolean hasDelayed = false;

    /**
     * Walk to proxy.
     */
    private IWalkToProxy proxy;

    /**
     * This will count up and progressively disable the entity
     */
    private int exceptionTimer = 1;

    /**
     * Slot he is currently trying to dump.
     */
    private int slotAt = 0;

    /**
     * Delay for walking.
     */
    protected static final int WALK_DELAY = 20;

    /**
     * What he currently might be needing.
     */
    protected Predicate<ItemStack> needsCurrently = null;

    /**
     * The current position the worker should walk to.
     */
    protected BlockPos walkTo = null;

    /**
     * Already kept items during the dumping cycle.
     */
    private final List<ItemStorage> alreadyKept = new ArrayList<>();

    /**
     * Paused state handler
     */
    private EntityAIStatePausedHandler pausedHandler;

    /**
     * Sets up some important skeleton stuff for every ai.
     *
     * @param job the job class
     */
    protected AbstractEntityAIBasic(@NotNull final J job)
    {
        super(job);
        super.registerTargets(
          /*
            Init safety checks and transition to IDLE
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, this::initSafetyChecks),
          /*
            Update chestbelt and nametag
            Will be executed every time
            and does not stop execution
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, this::updateVisualState),
          /*
            If waitingForSomething returns true
            stop execution to wait for it.
            this keeps the current state
            (returning null would not stop execution)
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, this::waitingForSomething, this::getState, 1),

          /*
            Dumps inventory as long as needs be.
            If inventory is dumped, execution continues
            to resolve state.
           */
          new AIEventTarget(AIBlockingEventType.STATE_BLOCKING, () -> (getState() == INVENTORY_FULL || this.inventoryNeedsDump()), this::dumpInventory),
          /*
            Check if any items are needed.
            If yes, transition to NEEDS_ITEM.
            and wait for new items.
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, () ->
                                                                  (getState() == NEEDS_ITEM
                                                                     || (this.getOwnBuilding().hasCitizenCompletedRequests(worker.getCitizenData())
                                                                           || this.getOwnBuilding()
                                                                                .hasWorkerOpenRequestsFiltered(worker.getCitizenData(),
                                                                                  r -> !worker.getCitizenData().isRequestAsync(r.getId())))
                                                                  ), this::waitForRequests),
          /*
           * Gather a needed item.
           */
          new AITarget(GATHERING_REQUIRED_MATERIALS, this::getNeededItem, 20),
          /*
           * Place any non-restart regarding AITargets before this one
           * Restart AI, building etc.
           */
          new AIEventTarget(AIBlockingEventType.STATE_BLOCKING, this::shouldRestart, this::restart),
          /*
           * Reset if not paused.
           */
          new AITarget(PAUSED, () -> !this.isPaused(), () -> IDLE, 20),
          /*
           * Do not work if worker is paused
           */
          new AITarget(PAUSED, this::bePaused),
          /*
           * Start paused with inventory dump
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, this::isStartingPaused, INVENTORY_FULL)
        );
    }

    /**
     * Retrieve a material from the building.
     * For this go to the building if no position has been set.
     * Then check for the chest with the required material and set the position and return.
     * <p>
     * If the position has been set navigate to it.
     * On arrival transfer to inventory and return to StartWorking.
     *
     * @return the next state to transfer to.
     */
    private IAIState getNeededItem()
    {
        worker.getCitizenStatusHandler().setLatestStatus(new TranslationTextComponent(COM_MINECOLONIES_COREMOD_STATUS_GATHERING));
        setDelay(STANDARD_DELAY);

        if (walkTo == null && walkToBuilding())
        {
            return getState();
        }


        if (needsCurrently == null)
        {
            return getStateAfterPickUp();
        }
        else
        {
            if (walkTo == null)
            {
                final BlockPos pos = getOwnBuilding().getTileEntity().getPositionOfChestWithItemStack(needsCurrently);
                if (pos == null)
                {
                    return getStateAfterPickUp();
                }
                walkTo = pos;
            }

            if (walkToBlock(walkTo) && !worker.getCitizenStuckHandler().isStuck())
            {
                setDelay(2);
                return getState();
            }

            final boolean transferred = tryTransferFromPosToWorker(walkTo, needsCurrently);
            if (!transferred)
            {
                walkTo = null;
                return getStateAfterPickUp();
            }
            walkTo = null;
        }

        return getStateAfterPickUp();
    }

    /**
     * The state to transform to after picking up things.
     *
     * @return the next state to go to.
     */
    public IAIState getStateAfterPickUp()
    {
        return START_WORKING;
    }

    @Nullable
    public <W extends AbstractBuildingWorker> W getOwnBuilding()
    {
        final IBuildingWorker worker = getOwnBuilding(getExpectedBuildingClass());
        return worker == null ? null : (W) worker;
    }

    /**
     * Can be overridden in implementations to return the exact building type the worker expects.
     *
     * @return the building type associated with this AI's worker.
     */
    public Class getExpectedBuildingClass()
    {
        return AbstractBuildingWorker.class;
    }

    /**
     * Can be overridden in implementations to return the exact building type.
     *
     * @return the building associated with this AI's worker.
     */
    @Nullable
    public <W extends AbstractBuildingWorker> W getOwnBuilding(@NotNull final Class<W> type)
    {
        if (type.isInstance(worker.getCitizenColonyHandler().getWorkBuilding()))
        {
            return (W) worker.getCitizenColonyHandler().getWorkBuilding();
        }
        else
        {
            if (worker.getCitizenData() != null)
            {
                worker.getCitizenData().setJob(null);
            }
        }
        return null;
    }

    @Override
    protected void onException(final RuntimeException e)
    {
        try
        {
            final int timeout = EXCEPTION_TIMEOUT * exceptionTimer;
            this.setDelay(timeout);
            // wait for longer now
            exceptionTimer *= 2;
            if (worker != null)
            {
                final String name = this.worker.getName();
                final BlockPos workerPosition = worker.getPosition();
                final IJob colonyJob = worker.getCitizenJobHandler().getColonyJob();
                final String jobName = colonyJob == null ? "null" : colonyJob.getName();
                Log.getLogger().error("Pausing Entity " + name + " (" + jobName + ") at " + workerPosition + " for " + timeout + " Seconds because of error:");
            }
            else
            {
                Log.getLogger().error("Pausing Entity that is null for " + timeout + " Seconds because of error:");
            }

            // fix for printing the actual exception
            e.printStackTrace();
        }
        catch (final RuntimeException exp)
        {
            Log.getLogger().error("Welp reporting crashed:");
            exp.printStackTrace();
            Log.getLogger().error("Caused by ai exception:");
            e.printStackTrace();
        }
    }

    /**
     * Set a delay in ticks.
     *
     * @param timeout the delay to wait after this tick.
     */
    protected final void setDelay(final int timeout)
    {
        this.delay = timeout;
    }

    /**
     * Check if we need to dump the worker inventory.
     * <p>
     * This will also ask the implementing ai
     * if we need to dump on custom reasons.
     * {@see wantInventoryDumped}
     *
     * @return true if we need to dump the inventory.
     */
    private boolean inventoryNeedsDump()
    {
        return getState() != INVENTORY_FULL &&
                 (worker.getCitizenInventoryHandler().isInventoryFull()
                    || job.getActionsDone() >= getActionsDoneUntilDumping()
                    || wantInventoryDumped())
                 && !(job instanceof JobDeliveryman);
    }

    /**
     * Calculates after how many actions the ai should dump it's inventory.
     * <p>
     * Override this to change the value.
     *
     * @return the number of actions done before item dump.
     */
    protected int getActionsDoneUntilDumping()
    {
        return ACTIONS_UNTIL_DUMP;
    }

    /**
     * Has to be overridden by classes to specify when to dump inventory.
     * Always dump on inventory full.
     *
     * @return true if inventory needs to be dumped now
     */
    protected boolean wantInventoryDumped()
    {
        return false;
    }

    /**
     * Check for null on important variables to prevent crashes.
     *
     * @return IDLE if all ready, else stay in INIT
     */
    @Nullable
    private IAIState initSafetyChecks()
    {
        if (null == getOwnBuilding())
        {
            if (getState() == INIT)
            {
                return INIT;
            }

            return IDLE;
        }

        if (getState() == INIT)
        {
            return IDLE;
        }

        return null;
    }

    /**
     * Updates the visual state of the worker.
     * Updates render meta data.
     * Updates the current state on the nametag.
     *
     * @return null to execute more targets.
     */
    private IAIState updateVisualState()
    {
        //Update the current state the worker is in.
        job.setNameTag(this.getState().toString());
        //Update torch, seeds etc. in chestbelt etc.
        updateRenderMetaData();
        return null;
    }

    /**
     * Can be overridden in implementations.
     * <p>
     * Here the AI can check if the chestBelt has to be re rendered and do it.
     */
    protected void updateRenderMetaData()
    {
        worker.setRenderMetadata("");
    }

    /**
     * This method will return true if the AI is waiting for something.
     * In that case, don't execute any more AI code, until it returns false.
     * Call this exactly once per tick to get the delay right.
     * The worker will move and animate correctly while he waits.
     *
     * @return true if we have to wait for something
     *
     * @see #currentStandingLocation @see #currentWorkingLocation
     */
    private boolean waitingForSomething()
    {
        if (delay > 0)
        {
            if (currentStandingLocation != null
                  && (!worker.getNavigator().noPath() || !worker.isWorkerAtSiteWithMove(currentStandingLocation, DEFAULT_RANGE_FOR_DELAY)))
            {
                //Don't decrease delay as we are just walking...
                return true;
            }
            if (delay % HIT_EVERY_X_TICKS == 0)
            {
                worker.getCitizenItemHandler().hitBlockWithToolInHand(currentWorkingLocation);
            }
            delay-= getTickRate();
            return true;
        }
        clearWorkTarget();
        return false;
    }

    /**
     * Remove the current working block and it's delay.
     */
    private void clearWorkTarget()
    {
        this.currentStandingLocation = null;
        this.currentWorkingLocation = null;
        this.delay = 0;
    }

    /**
     * If the worker has open requests their results will be queried until they all are completed
     * Also waits for DELAY_RECHECK.
     *
     * @return NEEDS_ITEM
     */
    @NotNull
    private IAIState waitForRequests()
    {
        delay = DELAY_RECHECK;
        updateWorkerStatusFromRequests();
        return lookForRequests();
    }

    private void updateWorkerStatusFromRequests()
    {
        if (!getOwnBuilding().hasWorkerOpenRequests(worker.getCitizenData()) && !getOwnBuilding().hasCitizenCompletedRequests(worker.getCitizenData()))
        {
            worker.getCitizenStatusHandler().setLatestStatus();
            return;
        }

        if ( getOwnBuilding().hasWorkerOpenRequests( worker.getCitizenData() ) )
        {
            for ( final IRequest request : getOwnBuilding().getOpenRequests( worker.getCitizenData() ) )
            {
                final IRequestResolver<?> resolver = worker.getCitizenColonyHandler().getColony().getRequestManager().getResolverForRequest(request.getId());
                if (resolver instanceof IPlayerRequestResolver || resolver instanceof IRetryingRequestResolver)
                {
                    if ( worker.getCitizenData().isRequestAsync(request.getId()) )
                    {
                        worker.getCitizenData().triggerInteraction(new RequestBasedInteractionResponseHandler(new TranslationTextComponent(ASYNC_REQUEST,
                          request.getShortDisplayString().getFormattedText()), ChatPriority.PENDING, new TranslationTextComponent(NORMAL_REQUEST), request.getId()));
                    }
                    else
                    {
                        worker.getCitizenData().triggerInteraction(new RequestBasedInteractionResponseHandler(new TranslationTextComponent(NORMAL_REQUEST,
                          request.getShortDisplayString().getFormattedText()), ChatPriority.BLOCKING, new TranslationTextComponent(NORMAL_REQUEST), request.getId()));
                    }
                }
            }
        }

        IRequest<?> request = getOwnBuilding().getCompletedRequests(worker.getCitizenData()).stream().findFirst().orElse(null);
        if (request == null)
        {
            request = getOwnBuilding().getOpenRequests(worker.getCitizenData()).stream().findFirst().orElse(null);
        }

        worker.getCitizenStatusHandler().setLatestStatus(new TranslationTextComponent("com.minecolonies.coremod.status.waiting"), request.getShortDisplayString());
    }

    /**
     * Utility method to search for items currently needed.
     * Poll this until all items are there.
     */
    @NotNull
    private IAIState lookForRequests()
    {
        if (!this.getOwnBuilding().hasWorkerOpenRequestsFiltered(worker.getCitizenData(), r -> !worker.getCitizenData().isRequestAsync(r.getId()))
              && !getOwnBuilding().hasCitizenCompletedRequests(worker.getCitizenData()))
        {
            return afterRequestPickUp();
        }
        if (!walkToBuilding() && getOwnBuilding().hasCitizenCompletedRequests(worker.getCitizenData()))
        {
            delay += DELAY_RECHECK;

            @SuppressWarnings(RAWTYPES) final ImmutableList<IRequest> completedRequests = getOwnBuilding().getCompletedRequests(worker.getCitizenData());

            completedRequests.stream().filter(r -> !(r.canBeDelivered())).forEach(r -> getOwnBuilding().markRequestAsAccepted(worker.getCitizenData(), r.getId()));
            final IRequest<?> firstDeliverableRequest = completedRequests.stream().filter(IRequest::canBeDelivered).findFirst().orElse(null);

            if (firstDeliverableRequest != null)
            {
                boolean async = false;
                if (worker.getCitizenData().isRequestAsync(firstDeliverableRequest.getId()))
                {
                    async = true;
                    job.getAsyncRequests().remove(firstDeliverableRequest.getId());
                }

                getOwnBuilding().markRequestAsAccepted(worker.getCitizenData(), firstDeliverableRequest.getId());
                final List<IItemHandler> validHandlers = Lists.newArrayList();
                validHandlers.add(worker.getItemHandlerCitizen());
                validHandlers.addAll(InventoryUtils.getItemHandlersFromProvider(getOwnBuilding()));

                //Check if we either have the requested Items in our inventory or if they are in the building.
                if (InventoryUtils.areAllItemsInItemHandlerList(firstDeliverableRequest.getDeliveries(), validHandlers))
                {
                    final List<ItemStack> niceToHave = itemsNiceToHave();
                    final List<ItemStack> contained = InventoryUtils.getContainedFromItemHandler(firstDeliverableRequest.getDeliveries(), worker.getItemHandlerCitizen());

                    InventoryUtils.moveItemStacksWithPossibleSwap(
                      worker.getItemHandlerCitizen(),
                      InventoryUtils.getItemHandlersFromProvider(getOwnBuilding()),
                      firstDeliverableRequest.getDeliveries(),
                      itemStack ->
                        contained.stream().anyMatch(stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(itemStack, stack)) ||
                        niceToHave.stream().anyMatch(stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(itemStack, stack))
                    );
                    return NEEDS_ITEM;
                }
                else
                {
                    //Seems like somebody else picked up our stack.
                    //Lets try this again.
                    if (async)
                    {
                        worker.getCitizenData().createRequestAsync(firstDeliverableRequest.getRequest());
                    }
                    else
                    {
                        worker.getCitizenData().createRequest(firstDeliverableRequest.getRequest());
                    }
                }
            }
        }

        return NEEDS_ITEM;
    }

    /**
     * What to do after picking up a request.
     *
     * @return the next state to go to.
     */
    public IAIState afterRequestPickUp()
    {
        return IDLE;
    }

    /**
     * Get the total amount required for an itemStack.
     * Workers have to override this if they have more information.
     *
     * @param deliveredItemStack the required stack.
     * @return on default the size of the stack.
     */
    public int getTotalRequiredAmount(final ItemStack deliveredItemStack)
    {
        return deliveredItemStack.getCount();
    }

    /**
     * Walk the worker to it's building chest.
     * Please return immediately if this returns true.
     *
     * @return false if the worker is at his building
     */
    protected final boolean walkToBuilding()
    {
        @Nullable final IBuildingWorker ownBuilding = getOwnBuilding();
        //Return true if the building is null to stall the worker
        return ownBuilding == null
                 || walkToBlock(ownBuilding.getPosition());
    }

    /**
     * Check all chests in the workers hut for a required item matching a certain predicate
     *
     * @param is the type of item requested (amount is ignored)
     * @return true if a stack of that type was found
     */
    public boolean isInHut(@Nullable final Predicate<ItemStack> is)
    {
        @Nullable final IBuildingWorker building = getOwnBuilding();

        boolean hasItem;
        if (building != null)
        {
            hasItem = building.getTileEntity().hasItemStack(is);

            if (hasItem)
            {
                return true;
            }

            for (final BlockPos pos : building.getAdditionalCountainers())
            {
                final TileEntity entity = world.getTileEntity(pos);
                if (entity instanceof TileEntityChest)
                {
                    hasItem = isInTileEntity(entity, is);

                    if (hasItem)
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Check all chests in the workers hut for a required item.
     *
     * @param is the type of item requested (amount is ignored)
     * @return true if a stack of that type was found
     */
    public boolean isInHut(@Nullable final ItemStack is)
    {
        @Nullable final IBuildingWorker building = getOwnBuilding();

        boolean hasItem;
        if (building != null)
        {
            hasItem = isInTileEntity(building.getTileEntity(), is);

            if (hasItem)
            {
                return true;
            }

            for (final BlockPos pos : building.getAdditionalCountainers())
            {
                final TileEntity entity = world.getTileEntity(pos);
                if (entity instanceof TileEntityChest)
                {
                    hasItem = isInTileEntity(entity, is);

                    if (hasItem)
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Sets the block the AI is currently walking to.
     *
     * @param stand where to walk to
     * @return true while walking to the block
     */
    protected final boolean walkToBlock(@NotNull final BlockPos stand)
    {
        return walkToBlock(stand, DEFAULT_RANGE_FOR_DELAY);
    }

    public boolean isInTileEntity(final AbstractTileEntityColonyBuilding entity, @NotNull final Predicate<ItemStack> itemStackSelectionPredicate)
    {
        return isInTileEntity((TileEntity) entity, itemStackSelectionPredicate);
    }

    /**
     * Finds the first @see ItemStack the type of {@code is}.
     * It will be taken from the chest and placed in the worker inventory.
     * Make sure that the worker stands next the chest to not break immersion.
     * Also make sure to have inventory space for the stack.
     *
     * @param entity the tileEntity chest or building or rack.
     * @param is     the itemStack.
     * @return true if found the stack.
     */
    public boolean isInTileEntity(final TileEntity entity, final ItemStack is)
    {
        return is != null
                 && InventoryFunctions
                      .matchFirstInProviderWithAction(
                        entity,
                        stack -> !ItemStackUtils.isEmpty(stack) && ItemStackUtils.compareItemStacksIgnoreStackSize(is, stack, true, true),
                        this::takeItemStackFromProvider
                      );
    }

    /**
     * Sets the block the AI is currently walking to.
     *
     * @param stand where to walk to
     * @param range how close we need to be
     * @return true while walking to the block
     */
    protected final boolean walkToBlock(@NotNull final BlockPos stand, final int range)
    {
        if (proxy == null)
        {
            proxy = new EntityCitizenWalkToProxy(worker);
        }
        if (!proxy.walkToBlock(stand, range))
        {
            workOnBlock(null, stand, DELAY_RECHECK);
            return true;
        }
        return false;
    }

    /**
     * Sets the block the AI is currently working on.
     * This block will receive animation hits on delay.
     *
     * @param target  the block that will be hit
     * @param stand   the block the worker will walk to
     * @param timeout the time in ticks to hit the block
     */
    private void workOnBlock(@Nullable final BlockPos target, @Nullable final BlockPos stand, final int timeout)
    {
        this.currentWorkingLocation = target;
        this.currentStandingLocation = stand;
        this.delay = timeout;
    }

    public boolean isInTileEntity(final AbstractTileEntityColonyBuilding entity, final ItemStack is)
    {
        return isInTileEntity((TileEntity) entity, is);
    }

    /**
     * Finds the first @see ItemStack the type of {@code is}.
     * It will be taken from the chest and placed in the worker inventory.
     * Make sure that the worker stands next the chest to not break immersion.
     * Also make sure to have inventory space for the stack.
     *
     * @param entity                      the tileEntity chest or building.
     * @param itemStackSelectionPredicate the criteria.
     * @return true if found the stack.
     */
    public boolean isInTileEntity(final TileEntity entity, @NotNull final Predicate<ItemStack> itemStackSelectionPredicate)
    {
        return InventoryFunctions
                 .matchFirstInProviderWithAction(
                   entity,
                   itemStackSelectionPredicate,
                   this::takeItemStackFromProvider
                 );
    }

    /**
     * Finds the first @see ItemStack the type of {@code is}.
     * It will be taken from the chest and placed in the worker inventory.
     * Make sure that the worker stands next the chest to not break immersion.
     * Also make sure to have inventory space for the stack.
     *
     * @param entity   the tileEntity chest or building.
     * @param toolType the type of tool.
     * @param minLevel the min tool level.
     * @param maxLevel the max tool lev	el.
     * @return true if found the tool.
     */
    public boolean retrieveToolInTileEntity(final TileEntity entity, final IToolType toolType, final int minLevel, final int maxLevel)
    {
        if (ToolType.NONE.equals(toolType))
        {
            return false;
        }
        return InventoryFunctions.matchFirstInProviderWithAction(
          entity,
          stack -> ItemStackUtils.hasToolLevel(stack, toolType, minLevel, maxLevel),
          this::takeItemStackFromProvider
        );
    }

    /**
     * Takes whatever is in that slot of the worker chest and puts it in his inventory.
     * If the inventory is full, only the fitting part will be moved.
     * Beware this method shouldn't be private, because the generic access won't work within a lambda won't work else.
     *
     * @param provider  The provider to take from.
     * @param slotIndex The slot to take.
     */
    public void takeItemStackFromProvider(@NotNull final ICapabilityProvider provider, final int slotIndex)
    {
        InventoryUtils.transferItemStackIntoNextBestSlotFromProvider(provider, slotIndex, new InvWrapper(worker.getInventoryCitizen()));
    }

    /**
     * Ensures that we have a appropriate tool available.
     * Will set {@code needsTool} accordingly.
     *
     * @param toolType type of tool we check for.
     * @return false if we have the tool
     */
    protected boolean checkForToolOrWeapon(@NotNull final IToolType toolType)
    {
        return checkForToolOrWeapon(toolType, TOOL_LEVEL_WOOD_OR_GOLD);
    }

    protected boolean checkForToolOrWeapon(@NotNull final IToolType toolType, final int minimalLevel)
    {
        final ImmutableList<IRequest<? extends Tool>> openToolRequests =
          getOwnBuilding().getOpenRequestsOfTypeFiltered(
            worker.getCitizenData(),
            TypeToken.of(Tool.class),
            r -> r.getRequest().getToolClass().equals(toolType) && r.getRequest().getMinLevel() >= minimalLevel);
        final ImmutableList<IRequest<? extends Tool>> completedToolRequests =
          getOwnBuilding().getCompletedRequestsOfTypeFiltered(
            worker.getCitizenData(),
            TypeToken.of(Tool.class),
            r -> r.getRequest().getToolClass().equals(toolType) && r.getRequest().getMinLevel() >= minimalLevel);

        if (checkForNeededTool(toolType, minimalLevel))
        {
            if (openToolRequests.isEmpty() && completedToolRequests.isEmpty())
            {
                final Tool request = new Tool(toolType, minimalLevel, getOwnBuilding().getMaxToolLevel() < minimalLevel ? minimalLevel : getOwnBuilding().getMaxToolLevel());
                worker.getCitizenData().createRequest(request);
            }
            return true;
        }

        return false;
    }

    /**
     * Ensures that we have a appropriate tool available.
     * ASync call on the tool.
     *
     * @param toolType     Tool type that is requested
     * @param minimalLevel min. level of the tool
     * @param maximalLevel min. level of the tool
     */
    protected void checkForToolorWeaponASync(@NotNull final IToolType toolType, final int minimalLevel, final int maximalLevel)
    {
        final ImmutableList<IRequest<? extends Tool>> openToolRequests =
          getOwnBuilding().getOpenRequestsOfTypeFiltered(
            worker.getCitizenData(),
            TypeToken.of(Tool.class),
            r -> r.getRequest().getToolClass().equals(toolType) && r.getRequest().getMinLevel() >= minimalLevel);
        final ImmutableList<IRequest<? extends Tool>> completedToolRequests =
          getOwnBuilding().getCompletedRequestsOfTypeFiltered(
            worker.getCitizenData(),
            TypeToken.of(Tool.class),
            r -> r.getRequest().getToolClass().equals(toolType) && r.getRequest().getMinLevel() >= minimalLevel);

        if (openToolRequests.isEmpty() && completedToolRequests.isEmpty() && !hasOpenToolRequest(toolType))
        {
            final Tool request = new Tool(toolType, minimalLevel, maximalLevel);
            worker.getCitizenData().createRequestAsync(request);
        }
    }

    /**
     * Cancel all requests for a certain armor type for a certain citizen.
     *
     * @param armorType the armor type.
     */
    protected void cancelAsynchRequestForArmor(final IToolType armorType)
    {
        final List<IRequest<? extends Tool>> openRequests =
          getOwnBuilding().getOpenRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.TOOL, iRequest -> iRequest.getRequest().getToolClass() == armorType);
        for (final IRequest token : openRequests)
        {
            worker.getCitizenColonyHandler().getColony().getRequestManager().updateRequestState(token.getId(), RequestState.CANCELLED);
        }
    }

    /**
     * Check if there is an open request for a certain tooltype.
     *
     * @param key the tooltype.
     * @return true if so.
     */
    private boolean hasOpenToolRequest(final IToolType key)
    {
        return getOwnBuilding().hasWorkerOpenRequestsFiltered(worker.getCitizenData(),
          iRequest -> iRequest.getRequest() instanceof Tool && ((Tool) iRequest.getRequest()).getToolClass() == key);
    }

    /**
     * Check if we need a tool.
     * <p>
     * Do not use it to find a pickaxe as it need a minimum level.
     *
     * @param toolType     tool required for block.
     * @param minimalLevel the minimal level.
     * @return true if we need a tool.
     */
    private boolean checkForNeededTool(@NotNull final IToolType toolType, final int minimalLevel)
    {
        final int maxToolLevel = worker.getCitizenColonyHandler().getWorkBuilding().getMaxToolLevel();
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        if (InventoryUtils.isToolInItemHandler(new InvWrapper(inventory), toolType, minimalLevel, maxToolLevel))
        {
            return false;
        }

        delay += DELAY_RECHECK;
        return walkToBuilding() || !retrieveToolInHut(toolType, minimalLevel);
    }

    /**
     * Check all chests in the workers hut for a required tool.
     *
     * @param toolType     the type of tool requested (amount is ignored)
     * @param minimalLevel the minimal level the tool should have.
     * @return true if a stack of that type was found
     */
    public boolean retrieveToolInHut(final IToolType toolType, final int minimalLevel)
    {
        @Nullable final IBuildingWorker building = getOwnBuilding();

        if (building != null)
        {
            if (retrieveToolInTileEntity(building.getTileEntity(), toolType, minimalLevel, getOwnBuilding().getMaxToolLevel()))
            {
                return true;
            }

            for (final BlockPos pos : building.getAdditionalCountainers())
            {
                final TileEntity entity = world.getTileEntity(pos);
                if (entity instanceof TileEntityChest && retrieveToolInTileEntity(entity, toolType, minimalLevel, getOwnBuilding().getMaxToolLevel()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Walk to building and dump inventory.
     * If inventory is dumped, continue execution
     * so that the state can be resolved.
     *
     * @return INVENTORY_FULL | IDLE
     */
    @NotNull
    private IAIState dumpInventory()
    {
        if (!worker.isWorkerAtSiteWithMove(getOwnBuilding().getPosition(), DEFAULT_RANGE_FOR_DELAY))
        {
            return INVENTORY_FULL;
        }

        if (InventoryUtils.isProviderFull(getOwnBuilding()))
        {
            if (!getOwnBuilding().isPriorityStatic())
            {
                getOwnBuilding().alterPickUpPriority(MAX_PRIO);
            }
            if ( worker.getCitizenData() != null )
            {
                worker.getCitizenData().triggerInteraction(new StandardInteractionResponseHandler(new TranslationTextComponent(COM_MINECOLONIES_COREMOD_ENTITY_WORKER_INVENTORYFULLCHEST), ChatPriority.IMPORTANT));
            }

        }
        else if (dumpOneMoreSlot())
        {
            delay += DELAY_RECHECK;
            return INVENTORY_FULL;
        }

        alreadyKept.clear();
        slotAt = 0;
        //collect items that are nice to have if they are available
        this.itemsNiceToHave().forEach(this::isInHut);
        // we dumped the inventory, reset actions done
        this.clearActionsDone();
        if (!getOwnBuilding().isPriorityStatic())
        {
            getOwnBuilding().alterPickUpPriority(1);
        }
        return afterDump();
    }

    /**
     * State to go to after dumping.
     *
     * @return the next state.
     */
    public IAIState afterDump()
    {
        if (isPaused())
        {
            // perform a cleanUp before going to PAUSED
            this.getOwnBuilding().onCleanUp(worker.getCitizenData());
            return PAUSED;
        }
        return IDLE;
    }

    /**
     * Dumps one inventory slot into the building chest.
     *
     * @return true if is has to dump more.
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    private boolean dumpOneMoreSlot()
    {
        if (walkToBuilding())
        {
            return true;
        }

        @Nullable final IBuildingWorker buildingWorker = getOwnBuilding();

        ItemStack stackToDump = worker.getInventoryCitizen().getStackInSlot(slotAt);
        final int totalSize = worker.getInventoryCitizen().getSizeInventory();

        while (stackToDump.isEmpty())
        {
            if (slotAt >= totalSize)
            {
                return false;
            }
            slotAt++;
            stackToDump = worker.getInventoryCitizen().getStackInSlot(slotAt);
        }

        boolean dumpAnyway = false;
        if (slotAt + MIN_OPEN_SLOTS * 2 >= totalSize)
        {
            final long openSlots = InventoryUtils.openSlotCount(new InvWrapper(worker.getInventoryCitizen()));
            if (openSlots < MIN_OPEN_SLOTS * 2)
            {
                if (stackToDump.getCount() < CHANCE_TO_DUMP_50)
                {
                    dumpAnyway = worker.getRandom().nextBoolean();
                }
                else
                {
                    dumpAnyway = worker.getRandom().nextInt(stackToDump.getCount()) < CHANCE_TO_DUMP;
                }
            }
        }

        if (buildingWorker != null && !ItemStackUtils.isEmpty(stackToDump))
        {
            final int amount = dumpAnyway ? stackToDump.getCount() : buildingWorker.buildingRequiresCertainAmountOfItem(stackToDump, alreadyKept, true);
            if (amount > 0)
            {
                final ItemStack activeStack = new InvWrapper(getInventory()).extractItem(slotAt, amount, false);
                InventoryUtils.transferItemStackIntoNextBestSlotInItemHandler(activeStack, buildingWorker.getCapability(ITEM_HANDLER_CAPABILITY, null));
            }
        }
        slotAt++;

        return slotAt < totalSize;
    }

    /**
     * Can be overridden by implementations to specify items useful for the worker.
     * When the worker inventory is full, he will try to keep these items.
     * ItemStack amounts are ignored, the first stack found will be taken.
     *
     * @return a list with items nice to have for the worker
     */
    @NotNull
    protected List<ItemStack> itemsNiceToHave()
    {
        return new ArrayList<>();
    }

    /**
     * Clear the actions done counter.
     * Call this when dumping into the chest.
     */
    private void clearActionsDone()
    {
        job.clearActionsDone();
    }

    /**
     * Calculate the citizens inventory.
     *
     * @return A InventoryCitizen matching this ai's citizen.
     */
    @NotNull
    protected InventoryCitizen getInventory()
    {
        return worker.getInventoryCitizen();
    }

    /**
     * Check and ensure that we hold the most efficient tool for the job.
     * <p>
     * If we have no tool for the job, we will request on, return immediately.
     *
     * @param target the block to mine
     * @param pos    the pos to mine
     * @return true if we have a tool for the job
     */
    public final boolean holdEfficientTool(@NotNull final Block target, final BlockPos pos)
    {
        final int bestSlot = getMostEfficientTool(target);
        if (bestSlot >= 0)
        {
            worker.getCitizenItemHandler().setHeldItem(EnumHand.MAIN_HAND, bestSlot);
            return true;
        }
        requestTool(target, pos);
        return false;
    }

    /**
     * Request the appropriate tool for this block.
     *
     * @param target the block to mine
     * @param pos    the pos to mine
     */
    private void requestTool(@NotNull final Block target, final BlockPos pos)
    {
        final IToolType toolType = WorkerUtil.getBestToolForBlock(target);
        final int required = WorkerUtil.getCorrectHavestLevelForBlock(target);
        if (getOwnBuilding().getMaxToolLevel() < required && worker.getCitizenData() != null)
        {
            worker.getCitizenData().triggerInteraction(new PosBasedInteractionResponseHandler(
              new TranslationTextComponent(BUILDING_LEVEL_TOO_LOW, new ItemStack(target).getDisplayName(), pos.getX(), pos.getY(), pos.getZ()), ChatPriority.IMPORTANT, new TranslationTextComponent(BUILDING_LEVEL_TOO_LOW), pos));
        }
        updateToolFlag(toolType, required);
    }

    /**
     * Checks if said tool of said level is usable.
     * if not, it updates the needsTool flag for said tool.
     *
     * @param toolType the tool needed
     * @param required the level needed (for pickaxe only)
     */
    private void updateToolFlag(@NotNull final IToolType toolType, final int required)
    {
        if (ToolType.PICKAXE.equals(toolType))
        {
            checkForToolOrWeapon(toolType, required);
        }
        else
        {
            checkForToolOrWeapon(toolType);
        }
    }

    /**
     * Calculates the most efficient tool to use
     * on that block.
     *
     * @param target the Block type to mine
     * @return the slot with the best tool
     */
    protected int getMostEfficientTool(@NotNull final Block target)
    {
        final IToolType toolType = WorkerUtil.getBestToolForBlock(target);
        final int required = WorkerUtil.getCorrectHavestLevelForBlock(target);
        int bestSlot = -1;
        int bestLevel = Integer.MAX_VALUE;
        @NotNull final InventoryCitizen inventory = worker.getInventoryCitizen();
        final int maxToolLevel = worker.getCitizenColonyHandler().getWorkBuilding().getMaxToolLevel();

        for (int i = 0; i < new InvWrapper(worker.getInventoryCitizen()).getSlots(); i++)
        {
            final ItemStack item = inventory.getStackInSlot(i);
            final int level = ItemStackUtils.getMiningLevel(item, toolType);
            if (level >= required && level < bestLevel
                  && (toolType == ToolType.NONE || ItemStackUtils.verifyToolLevel(item, level, required, maxToolLevel)))
            {
                bestSlot = i;
                bestLevel = level;
            }
        }

        return bestSlot;
    }

    /**
     * Will delay one time and pass through the second time.
     * Use for convenience instead of SetDelay
     *
     * @param time the time to wait
     * @return true if you should wait
     */
    protected final boolean hasNotDelayed(final int time)
    {
        if (!hasDelayed)
        {
            setDelay(time);
            hasDelayed = true;
            return true;
        }
        hasDelayed = false;
        return false;
    }

    /**
     * Tell the ai that you have done one more action.
     * <p>
     * if the actions exceed a certain number,
     * the ai will dump it's inventory.
     * this also triggers the AI to get hungry.
     * <p>
     * For example:
     * <p>
     * After x blocks, bring everything back.
     */
    protected final void incrementActionsDoneAndDecSaturation()
    {
        worker.decreaseSaturationForAction();
        incrementActionsDone();
    }

    /**
     * Tell the ai that you have done one more action.
     * <p>
     * if the actions exceed a certain number,
     * the ai will dump it's inventory.
     * <p>
     * For example:
     * <p>
     * After x blocks, bring everything back.
     */
    protected final void incrementActionsDone()
    {
        job.incrementActionsDone();
    }

    /**
     * Calculates the working position.
     * <p>
     * Takes the position where the worker would like to work on and return the most appropriate position for it.
     * <p>
     *
     * @param targetPosition the position to work at.
     * @return BlockPos most appropiate position to work from.
     */
    public BlockPos getWorkingPosition(final BlockPos targetPosition)
    {
        return targetPosition;
    }

    /**
     * Calculates the working position.
     * <p>
     * Takes a min distance from width and length.
     * <p>
     * Then finds the floor level at that distance and then check if it does contain two air levels.
     *
     * @param distance  the extra distance to apply away from the building.
     * @param targetPos the target position which needs to be worked.
     * @param offset    an additional offset
     * @return BlockPos position to work from.
     */
    public BlockPos getWorkingPosition(final int distance, final BlockPos targetPos, final int offset)
    {
        if (offset > MAX_ADDITIONAL_RANGE_TO_BUILD)
        {
            return targetPos;
        }

        @NotNull final EnumFacing[] directions = {EnumFacing.EAST, EnumFacing.WEST, EnumFacing.NORTH, EnumFacing.SOUTH};

        //then get a solid place with two air spaces above it in any direction.
        for (final EnumFacing direction : directions)
        {
            @NotNull final BlockPos positionInDirection = getPositionInDirection(direction, distance + offset, targetPos);
            if (EntityUtils.checkForFreeSpace(world, positionInDirection)
                  && world.getBlockState(positionInDirection.up()).getBlock() != Blocks.SAPLING)
            {
                return positionInDirection;
            }
        }

        //if necessary we call it recursively and add some "offset" to the sides.
        return getWorkingPosition(distance, targetPos, offset + 1);
    }

    /**
     * Gets a floorPosition in a particular direction.
     *
     * @param facing    the direction.
     * @param distance  the distance.
     * @param targetPos the position to work at.
     * @return a BlockPos position.
     */
    @NotNull
    private BlockPos getPositionInDirection(final EnumFacing facing, final int distance, final BlockPos targetPos)
    {
        return BlockPosUtil.getFloor(targetPos.offset(facing, distance), world);
    }

    /**
     * Requests a list of itemstacks.
     *
     * @param stacks the stacks.
     * @return true if they're in the inventory.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final ItemStack... stacks)
    {
        return checkIfRequestForItemExistOrCreate(Lists.newArrayList(stacks));
    }

    /**
     * Check if any of the stacks is in the inventory.
     *
     * @param stacks the list of stacks.
     * @return true if so.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final Collection<ItemStack> stacks)
    {
        return stacks.stream().allMatch(this::checkIfRequestForItemExistOrCreate);
    }

    /**
     * Check if a stack has been requested already or is in the inventory.
     * If not in the inventory and not requested already, create request
     *
     * @param stack the requested stack.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final ItemStack stack)
    {
        if (InventoryUtils.hasItemInItemHandler(new InvWrapper(worker.getInventoryCitizen()),
          s -> ItemStackUtils.compareItemStacksIgnoreStackSize(s, stack)))
        {
            return true;
        }

        if (getOwnBuilding().getOpenRequestsOfTypeFiltered(worker.getCitizenData(),
          TypeConstants.DELIVERABLE,
          (IRequest<? extends IDeliverable> r) -> r.getRequest().matches(stack)).isEmpty())
        {
            final Stack stackRequest = new Stack(stack);
            worker.getCitizenData().createRequest(stackRequest);
        }

        return false;
    }

    /**
     * Requests a list of itemstacks.
     *
     * @param stacks the stacks.
     * @return true if they're in the inventory.
     */
    public boolean checkIfRequestForItemExistOrCreateAsynch(@NotNull final ItemStack... stacks)
    {
        return checkIfRequestForItemExistOrCreateAsynch(Lists.newArrayList(stacks));
    }

    /**
     * Check if any of the stacks is in the inventory.
     *
     * @param stacks the list of stacks.
     * @return true if so.
     */
    public boolean checkIfRequestForItemExistOrCreateAsynch(@NotNull final Collection<ItemStack> stacks)
    {
        return stacks.stream().allMatch(this::checkIfRequestForItemExistOrCreateAsynch);
    }

    /**
     * Check if a stack has been requested already or is in the inventory.
     * If not in the inventory and not requested already, create request
     *
     * @param stack the requested stack.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForItemExistOrCreateAsynch(@NotNull final ItemStack stack)
    {
        if (InventoryUtils.hasItemInItemHandler(new InvWrapper(worker.getInventoryCitizen()),
          s -> ItemStackUtils.compareItemStacksIgnoreStackSize(s, stack) && s.getCount() >= stack.getCount()))
        {
            return true;
        }

        if (InventoryUtils.getItemCountInProvider(getOwnBuilding(),
          itemStack -> ItemStackUtils.compareItemStacksIgnoreStackSize(itemStack, stack, true, true)) >= stack.getCount() &&
              InventoryUtils.transferXOfFirstSlotInProviderWithIntoNextFreeSlotInItemHandler(
                getOwnBuilding(), itemStack -> ItemStackUtils.compareItemStacksIgnoreStackSize(itemStack, stack, true, true),
                stack.getCount(),
                new InvWrapper(worker.getInventoryCitizen())))
        {
            return true;
        }

        if (getOwnBuilding().getOpenRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.DELIVERABLE,
          (IRequest<? extends IDeliverable> r) -> r.getRequest().matches(stack)).isEmpty())
        {
            final Stack stackRequest = new Stack(stack);
            worker.getCitizenData().createRequestAsync(stackRequest);
        }

        return false;
    }

    /**
     * Try to transfer a item matching a predicate from a position to the cook.
     *
     * @param pos       the position to transfer it from.
     * @param predicate the predicate to evaluate.
     * @return true if succesful.
     */
    private boolean tryTransferFromPosToWorker(final BlockPos pos, @NotNull final Predicate<ItemStack> predicate)
    {
        final TileEntity entity = world.getTileEntity(pos);
        if (entity == null)
        {
            return false;
        }

        return InventoryUtils.transferXOfFirstSlotInProviderWithIntoNextFreeSlotInItemHandler(
          entity,
          predicate,
          Constants.STACKSIZE,
          new InvWrapper(worker.getInventoryCitizen()));
    }

    /**
     * Is worker paused?
     *
     * @return true if paused
     */
    private boolean isPaused()
    {
        return worker.getCitizenData().isPaused();
    }

    /**
     * Is worker starting paused
     *
     * @return true if starting paused
     */
    private boolean isStartingPaused()
    {
        return isPaused() && getState() != PAUSED && getState() != INVENTORY_FULL;
    }

    /**
     * Worker executes {@link EntityAIStatePausedHandler}.
     *
     * @return <code>State.PAUSED</code>
     */
    private IAIState bePaused()
    {
        if (pausedHandler == null)
        {
            pausedHandler = new EntityAIStatePausedHandler(worker, getOwnBuilding());
        }

        pausedHandler.doPause();
        setDelay(WALK_DELAY);
        return PAUSED;
    }

    /**
     * Is worker paused but not walking.
     *
     * @return true if restart is scheduled
     */
    private boolean shouldRestart()
    {
        return worker.getCitizenData().shouldRestart() && this.isPaused();
    }

    /**
     * Restart AI, building etc.
     *
     * @return <code>State.INIT</code>
     */
    private IAIState restart()
    {
        this.getOwnBuilding().onCleanUp(worker.getCitizenData());
        this.getOwnBuilding().onRestart(worker.getCitizenData());
        setDelay(WALK_DELAY);
        worker.getCitizenData().restartDone();
        return INIT;
    }
}
