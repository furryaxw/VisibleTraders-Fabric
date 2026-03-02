package net.ramixin.visibletraders;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.ramixin.visibletraders.ducks.VillagerDuck;
import net.ramixin.visibletraders.threading.FutureMerchantOffer;
import net.ramixin.visibletraders.threading.SerializableListing;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class LockedTradeData {

    private List<MerchantOffers> lockedOffers;

    public LockedTradeData(Villager villager) {
        this.lockedOffers = generateTrades(villager);
    }

    private LockedTradeData(List<MerchantOffers> offers) {
        this.lockedOffers = new ArrayList<>(offers);
    }

    @SuppressWarnings("resource")
    public static @Nullable LockedTradeData constructOrNull(CompoundTag nbt, Entity entity) {
        //noinspection resource
        if (!(entity.level() instanceof ServerLevel))
            return null;

        if (!nbt.contains("LockedOffers", Tag.TAG_LIST)) return null;

        ListTag offersListTag = nbt.getList("LockedOffers", Tag.TAG_COMPOUND);
        List<MerchantOffers> offers = new ArrayList<>();
        for (int i = 0; i < offersListTag.size(); i++) {
            offers.add(new MerchantOffers(offersListTag.getCompound(i)));
        }

        if (nbt.contains("futureOfferIndices", Tag.TAG_LONG_ARRAY)) {
            long[] indicesArray = nbt.getLongArray("futureOfferIndices");
            List<int[]> indices = new ArrayList<>();
            for (long lung : indicesArray) {
                indices.add(new int[]{(int) (lung >> 32), (int) (lung & 0xFF_FF_FF_FFL)});
            }

            Optional<List<SerializableListing>> maybeListings = VisibleTraders.CODEC.listOf().parse(NbtOps.INSTANCE, nbt.get("futureOffers")).result();
            if (maybeListings.isEmpty()) return null;

            List<SerializableListing> listings = maybeListings.get();
            if (listings.size() != indices.size()) return null;

            for (int i = 0; i < listings.size(); i++) {
                SerializableListing listing = listings.get(i);
                int[] index = indices.get(i);
                if (index[0] >= offers.size()) return null;
                MerchantOffers offerSet = offers.get(index[0]);
                FutureMerchantOffer futureOffer = new FutureMerchantOffer(listing, () -> listing.visibleTrades$buildOffer(entity, entity.level().getRandom()));
                VisibleTraders.TRADE_WORKER.addOrder(futureOffer);
                offerSet.add(index[1], futureOffer);
            }
        }
        return new LockedTradeData(offers);
    }

    private static ArrayList<MerchantOffers> generateTrades(Villager villager) {
        MerchantOffers offers = villager.getOffers();
        VillagerData data = villager.getVillagerData();
        ArrayList<MerchantOffers> lockedOffers = new ArrayList<>();
        int level = data.getLevel();
        while (level < 5) {
            villager.setVillagerData(data.setLevel(++level));
            int prev = offers.size();
            VillagerDuck.of(villager).visibleTraders$updateTrades();
            int dif = offers.size() - prev;
            MerchantOffers newOffers = new MerchantOffers();
            // Java 17 兼容写法：移除并替换 removeLast()
            for (int i = 0; i < dif; i++) newOffers.add(offers.remove(offers.size() - 1));
            lockedOffers.add(newOffers);
        }
        villager.setVillagerData(data);
        return lockedOffers;
    }

    public void write(CompoundTag nbt) {
        if (this.lockedOffers == null) return;
        ListTag offersToWrite = new ListTag();
        List<Long> futureOfferIndexes = new ArrayList<>();
        List<SerializableListing> futureOffers = new ArrayList<>();

        for (int i = 0; i < lockedOffers.size(); i++) {
            MerchantOffers offers = lockedOffers.get(i);
            MerchantOffers newOffers = new MerchantOffers();
            for (int j = 0; j < offers.size(); j++) {
                MerchantOffer offer = offers.get(j);
                if (!(offer instanceof FutureMerchantOffer futureOffer)) newOffers.add(offer);
                else {
                    if (futureOffer.isFulfilled())
                        newOffers.add(Objects.requireNonNull(futureOffer.getFuture(), "Future offers was null although fulfilled"));
                    else {
                        futureOfferIndexes.add(((long) i) << 32 | j);
                        futureOffers.add(futureOffer.getListing());
                    }
                }
            }
            // 1.20.1 中 MerchantOffers 直接提供 createTag() 生成序列化后的 Tag
            offersToWrite.add(newOffers.createTag());
        }

        nbt.put("LockedOffers", offersToWrite);

        if (!futureOfferIndexes.isEmpty()) {
            long[] indicesArray = futureOfferIndexes.stream().mapToLong(l -> l).toArray();
            nbt.putLongArray("futureOfferIndices", indicesArray);

            VisibleTraders.CODEC.listOf().encodeStart(NbtOps.INSTANCE, futureOffers)
                    .resultOrPartial(VisibleTraders.LOGGER::error)
                    .ifPresent(tag -> nbt.put("futureOffers", tag));
        }
    }

    public boolean hasNoOffers() {
        return this.lockedOffers.isEmpty();
    }

    public MerchantOffers popTradeSet() {
        if (this.lockedOffers == null || hasNoOffers()) return null;
        // Java 17 兼容写法：移除并替换 removeFirst()
        return this.lockedOffers.remove(0);
    }

    public Optional<MerchantOffers> peekTradeSet() {
        if (this.lockedOffers.isEmpty()) {
            return Optional.empty();
        }
        // Java 17 兼容写法：移除并替换 getFirst()
        return Optional.of(this.lockedOffers.get(0));
    }

    public MerchantOffers buildLockedOffers() {
        MerchantOffers lockedOffers = new MerchantOffers();
        if (this.lockedOffers == null) return lockedOffers;
        for (MerchantOffers listOffers : this.lockedOffers)
            for (MerchantOffer offer : listOffers) {
                if (offer.getResult().isEmpty() && !(offer instanceof FutureMerchantOffer)) {
                    this.lockedOffers = new ArrayList<>();
                    VisibleTraders.LOGGER.error("detected incomplete trade. Rebuilding locked offers");
                    return new MerchantOffers();
                }
                lockedOffers.add(offer);
            }
        return lockedOffers;
    }

    public void tick(Villager villager, Runnable popCallback) {
        if (this.lockedOffers == null) return;
        int requiredSets = 5 - villager.getVillagerData().getLevel();
        while (requiredSets < this.lockedOffers.size()) popCallback.run();
        if (requiredSets > this.lockedOffers.size()) {
            VisibleTraders.LOGGER.error("detected missing locked trade sets. Rebuilding locked offers");
            this.lockedOffers = generateTrades(villager);
        }
    }
}
