/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.presto.spi.block;

import com.facebook.presto.spi.type.Type;

import javax.annotation.Nullable;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Optional;

import static com.facebook.presto.spi.block.BlockUtil.checkArrayRange;
import static com.facebook.presto.spi.block.BlockUtil.checkValidRegion;
import static com.facebook.presto.spi.block.BlockUtil.compactArray;
import static com.facebook.presto.spi.block.BlockUtil.compactOffsets;
import static com.facebook.presto.spi.block.MapBlock.createMapBlockInternal;
import static java.util.Objects.requireNonNull;

public abstract class AbstractMapBlock
        implements Block
{
    // inverse of hash fill ratio, must be integer
    static final int HASH_MULTIPLIER = 2;

    protected final Type keyType;
    protected final MethodHandle keyNativeHashCode;
    protected final MethodHandle keyBlockNativeEquals;
    protected final MethodHandle keyBlockHashCode;

    public AbstractMapBlock(Type keyType, MethodHandle keyNativeHashCode, MethodHandle keyBlockNativeEquals, MethodHandle keyBlockHashCode)
    {
        this.keyType = requireNonNull(keyType, "keyType is null");
        // keyNativeHashCode can only be null due to map block kill switch. deprecated.new-map-block
        this.keyNativeHashCode = keyNativeHashCode;
        // keyBlockNativeEquals can only be null due to map block kill switch. deprecated.new-map-block
        this.keyBlockNativeEquals = keyBlockNativeEquals;
        this.keyBlockHashCode = requireNonNull(keyBlockHashCode, "keyBlockHashCode is null");
    }

    protected abstract Block getRawKeyBlock();

    protected abstract Block getRawValueBlock();

    @Nullable
    protected abstract int[] getHashTables();

    /**
     * offset is entry-based, not position-based. In other words,
     * if offset[1] is 6, it means the first map has 6 key-value pairs,
     * not 6 key/values (which would be 3 pairs).
     */
    protected abstract int[] getOffsets();

    /**
     * offset is entry-based, not position-based. (see getOffsets)
     */
    protected abstract int getOffsetBase();

    protected abstract boolean[] getMapIsNull();

    protected abstract void ensureHashTableLoaded();

    int getOffset(int position)
    {
        return getOffsets()[position + getOffsetBase()];
    }

    @Override
    public String getEncodingName()
    {
        return MapBlockEncoding.NAME;
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);

        int[] newOffsets = new int[length + 1];
        boolean[] newMapIsNull = new boolean[length];

        IntArrayList entriesPositions = new IntArrayList();
        int newPosition = 0;
        for (int i = offset; i < offset + length; ++i) {
            int position = positions[i];
            if (isNull(position)) {
                newMapIsNull[newPosition] = true;
                newOffsets[newPosition + 1] = newOffsets[newPosition];
            }
            else {
                int entriesStartOffset = getOffset(position);
                int entriesEndOffset = getOffset(position + 1);
                int entryCount = entriesEndOffset - entriesStartOffset;

                newOffsets[newPosition + 1] = newOffsets[newPosition] + entryCount;

                for (int elementIndex = entriesStartOffset; elementIndex < entriesEndOffset; elementIndex++) {
                    entriesPositions.add(elementIndex);
                }
            }
            newPosition++;
        }

        int[] hashTable = getHashTables();
        int[] newHashTable = null;
        if (hashTable != null) {
            newHashTable = new int[newOffsets[newOffsets.length - 1] * HASH_MULTIPLIER];
            int newHashIndex = 0;
            for (int i = offset; i < offset + length; ++i) {
                int position = positions[i];
                int entriesStartOffset = getOffset(position);
                int entriesEndOffset = getOffset(position + 1);
                for (int hashIndex = entriesStartOffset * HASH_MULTIPLIER; hashIndex < entriesEndOffset * HASH_MULTIPLIER; hashIndex++) {
                    newHashTable[newHashIndex] = hashTable[hashIndex];
                    newHashIndex++;
                }
            }
        }

        Block newKeys = getRawKeyBlock().copyPositions(entriesPositions.elements(), 0, entriesPositions.size());
        Block newValues = getRawValueBlock().copyPositions(entriesPositions.elements(), 0, entriesPositions.size());
        return createMapBlockInternal(
                0,
                length,
                Optional.of(newMapIsNull),
                newOffsets,
                newKeys,
                newValues,
                Optional.ofNullable(newHashTable),
                keyType,
                keyBlockNativeEquals,
                keyNativeHashCode,
                keyBlockHashCode);
    }

    @Override
    public Block getRegion(int position, int length)
    {
        int positionCount = getPositionCount();
        checkValidRegion(positionCount, position, length);

        return createMapBlockInternal(
                position + getOffsetBase(),
                length,
                Optional.ofNullable(getMapIsNull()),
                getOffsets(),
                getRawKeyBlock(),
                getRawValueBlock(),
                Optional.ofNullable(getHashTables()),
                keyType,
                keyBlockNativeEquals,
                keyNativeHashCode,
                keyBlockHashCode);
    }

    @Override
    public long getRegionSizeInBytes(int position, int length)
    {
        int positionCount = getPositionCount();
        checkValidRegion(positionCount, position, length);

        int entriesStart = getOffsets()[getOffsetBase() + position];
        int entriesEnd = getOffsets()[getOffsetBase() + position + length];
        int entryCount = entriesEnd - entriesStart;

        return getRawKeyBlock().getRegionSizeInBytes(entriesStart, entryCount) +
                getRawValueBlock().getRegionSizeInBytes(entriesStart, entryCount) +
                (Integer.BYTES + Byte.BYTES) * (long) length +
                Integer.BYTES * HASH_MULTIPLIER * (long) entryCount;
    }

    @Override
    public Block copyRegion(int position, int length)
    {
        int positionCount = getPositionCount();
        checkValidRegion(positionCount, position, length);

        int startValueOffset = getOffset(position);
        int endValueOffset = getOffset(position + length);
        Block newKeys = getRawKeyBlock().copyRegion(startValueOffset, endValueOffset - startValueOffset);
        Block newValues = getRawValueBlock().copyRegion(startValueOffset, endValueOffset - startValueOffset);

        int[] newOffsets = compactOffsets(getOffsets(), position + getOffsetBase(), length);
        boolean[] mapIsNull = getMapIsNull();
        boolean[] newMapIsNull = mapIsNull == null ? null : compactArray(mapIsNull, position + getOffsetBase(), length);

        int[] hashTables = getHashTables();
        int[] newHashTable = null;
        if (hashTables != null) {
            newHashTable = compactArray(hashTables, startValueOffset * HASH_MULTIPLIER, (endValueOffset - startValueOffset) * HASH_MULTIPLIER);
        }

        if (newKeys == getRawKeyBlock() && newValues == getRawValueBlock() && newOffsets == getOffsets() && newMapIsNull == mapIsNull && newHashTable == getHashTables()) {
            return this;
        }
        return createMapBlockInternal(
                0,
                length,
                Optional.ofNullable(newMapIsNull),
                newOffsets,
                newKeys,
                newValues,
                Optional.ofNullable(newHashTable),
                keyType,
                keyBlockNativeEquals,
                keyNativeHashCode,
                keyBlockHashCode);
    }

    @Override
    public <T> T getObject(int position, Class<T> clazz)
    {
        if (clazz != Block.class) {
            throw new IllegalArgumentException("clazz must be Block.class");
        }
        checkReadablePosition(position);

        int startEntryOffset = getOffset(position);
        int endEntryOffset = getOffset(position + 1);
        return clazz.cast(new SingleMapBlock(
                startEntryOffset * 2,
                (endEntryOffset - startEntryOffset) * 2,
                this));
    }

    @Override
    public void writePositionTo(int position, BlockBuilder blockBuilder)
    {
        checkReadablePosition(position);
        blockBuilder.appendStructureInternal(this, position);
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        checkReadablePosition(position);

        int startValueOffset = getOffset(position);
        int endValueOffset = getOffset(position + 1);
        int valueLength = endValueOffset - startValueOffset;
        Block newKeys = getRawKeyBlock().copyRegion(startValueOffset, valueLength);
        Block newValues = getRawValueBlock().copyRegion(startValueOffset, valueLength);

        int[] hashTables = getHashTables();
        int[] newHashTable = null;
        if (hashTables != null) {
            newHashTable = Arrays.copyOfRange(hashTables, startValueOffset * HASH_MULTIPLIER, endValueOffset * HASH_MULTIPLIER);
        }

        return createMapBlockInternal(
                0,
                1,
                Optional.of(new boolean[] {isNull(position)}),
                new int[] {0, valueLength},
                newKeys,
                newValues,
                Optional.ofNullable(newHashTable),
                keyType,
                keyBlockNativeEquals,
                keyNativeHashCode,
                keyBlockHashCode);
    }

    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        checkReadablePosition(position);

        if (isNull(position)) {
            return 0;
        }

        int startValueOffset = getOffset(position);
        int endValueOffset = getOffset(position + 1);

        long size = 0;
        Block rawKeyBlock = getRawKeyBlock();
        Block rawValueBlock = getRawValueBlock();
        for (int i = startValueOffset; i < endValueOffset; i++) {
            size += rawKeyBlock.getEstimatedDataSizeForStats(i);
            size += rawValueBlock.getEstimatedDataSizeForStats(i);
        }
        return size;
    }

    @Override
    public boolean isNull(int position)
    {
        checkReadablePosition(position);
        boolean[] mapIsNull = getMapIsNull();
        return mapIsNull != null && mapIsNull[position + getOffsetBase()];
    }

    private void checkReadablePosition(int position)
    {
        if (position < 0 || position >= getPositionCount()) {
            throw new IllegalArgumentException("position is not valid");
        }
    }
}
